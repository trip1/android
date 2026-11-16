// Package nebmobile is Transwarp's gomobile binding around slackhq/nebula: one Nebula instance per process, fed a
// YAML config and the VpnService tun fd. Logs go to stderr, which gomobile pipes to logcat (tag GoLog).
//
// Rebuild the AAR (needs Go 1.26 + NDK): ./nebula-mobile/build.sh
package nebmobile

import (
	"encoding/binary"
	"encoding/json"
	"log/slog"
	"net/netip"
	"os"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/slackhq/nebula"
	"github.com/slackhq/nebula/config"
	"github.com/slackhq/nebula/logging"
	"github.com/slackhq/nebula/overlay"
)

var (
	mu  sync.Mutex
	ctl *nebula.Control
	cfg *config.C
	cnt *counting
)

// counting wraps the tun device and counts IPv4 bytes per Nebula host, like WireGuard's per-peer rx/tx: a packet
// for an address in the Nebula network is that host's, one for an unsafe route is its gateway's (the hub).
// ponytail: IPv4 only (our overlay is v4); v6 packets aren't counted.
type counting struct {
	overlay.Device
	peers sync.Map // netip.Addr -> *peerCtr

	// Ping: our app is off the VPN (Nebula's socket must be), so its pings can't use the tun. Echo requests are
	// fed to Nebula as if read from the tun; the matching replies are caught in Write and never reach the OS.
	inject  chan []byte
	waiting sync.Map // ICMP echo id (uint16) -> chan struct{}
	pump    sync.Once
	rd      chan readResult
	ack     chan struct{}
	buf     []byte
}

type readResult struct {
	n   int
	err error
}

type peerCtr struct{ rx, tx, lastRx atomic.Int64 }

func (d *counting) peer(b []byte, off int) *peerCtr {
	if len(b) < 20 || b[0]>>4 != 4 {
		return nil
	}
	a := netip.AddrFrom4([4]byte(b[off : off+4]))
	inNet := false
	for _, n := range d.Networks() {
		inNet = inNet || n.Contains(a)
	}
	if !inNet {
		gw := d.RoutesFor(a)
		if len(gw) == 0 {
			return nil
		}
		a = gw[0].Addr()
	}
	v, _ := d.peers.LoadOrStore(a, &peerCtr{})
	return v.(*peerCtr)
}

// Read is a packet leaving this device (tx to its destination host), or an injected ping. A pump goroutine reads
// the tun so Read can also select on injected packets; ack hands the single buffer back.
func (d *counting) Read(b []byte) (int, error) {
	d.pump.Do(func() {
		go func() {
			for {
				n, err := d.Device.Read(d.buf)
				d.rd <- readResult{n, err} // buffered: the last send after Stop doesn't block
				if err != nil {
					return
				}
				<-d.ack
			}
		}()
	})
	select {
	case p := <-d.inject:
		return copy(b, p), nil
	case r := <-d.rd:
		n := copy(b, d.buf[:r.n])
		d.ack <- struct{}{}
		if p := d.peer(b[:n], 16); p != nil {
			p.tx.Add(int64(n))
		}
		return n, r.err
	}
}

// Write is a packet arriving from a host (rx from its source).
func (d *counting) Write(b []byte) (int, error) {
	if p := d.peer(b, 12); p != nil {
		p.rx.Add(int64(len(b)))
		p.lastRx.Store(time.Now().Unix())
	}
	if id, ok := echoReplyID(b); ok {
		if w, ok := d.waiting.LoadAndDelete(id); ok {
			close(w.(chan struct{}))
			return len(b), nil
		}
	}
	return d.Device.Write(b)
}

func newCounting(dev overlay.Device) *counting {
	return &counting{Device: dev, inject: make(chan []byte, 4), rd: make(chan readResult, 1), ack: make(chan struct{}),
		buf: make([]byte, 65535)}
}

// ping sends one ICMP echo through Nebula and waits for its reply: RTT in ms, -1 on timeout.
func (d *counting) ping(dst netip.Addr, timeout time.Duration) int {
	nets := d.Networks()
	if len(nets) == 0 || !dst.Is4() {
		return -1
	}
	id := uint16(time.Now().UnixNano())
	w := make(chan struct{})
	d.waiting.Store(id, w)
	defer d.waiting.Delete(id)
	start := time.Now()
	select {
	case d.inject <- echoRequest(nets[0].Addr(), dst, id):
	case <-time.After(timeout):
		return -1
	}
	select {
	case <-w:
		return int(time.Since(start).Milliseconds())
	case <-time.After(timeout - time.Since(start)):
		return -1
	}
}

// echoRequest is a 28-byte IPv4 ICMP echo request (id, seq 1) from src to dst.
func echoRequest(src, dst netip.Addr, id uint16) []byte {
	b := make([]byte, 28)
	b[0], b[3], b[8], b[9] = 0x45, 28, 64, 1 // v4/IHL 5, total length 28, TTL 64, proto ICMP
	s, t := src.As4(), dst.As4()
	copy(b[12:16], s[:])
	copy(b[16:20], t[:])
	binary.BigEndian.PutUint16(b[10:], checksum(b[:20]))
	b[20] = 8 // echo request
	binary.BigEndian.PutUint16(b[24:], id)
	binary.BigEndian.PutUint16(b[26:], 1)
	binary.BigEndian.PutUint16(b[22:], checksum(b[20:]))
	return b
}

func echoReplyID(b []byte) (uint16, bool) {
	if len(b) < 20 || b[0]>>4 != 4 || b[9] != 1 {
		return 0, false
	}
	ihl := int(b[0]&0xf) * 4
	if len(b) < ihl+8 || b[ihl] != 0 {
		return 0, false
	}
	return binary.BigEndian.Uint16(b[ihl+4:]), true
}

func checksum(b []byte) uint16 {
	var s uint32
	for i := 0; i+1 < len(b); i += 2 {
		s += uint32(b[i])<<8 | uint32(b[i+1])
	}
	if len(b)%2 == 1 {
		s += uint32(b[len(b)-1]) << 8
	}
	for s>>16 != 0 {
		s = s&0xffff + s>>16
	}
	return ^uint16(s)
}

// Ping is one ICMP echo to ip through the running Nebula tunnel: RTT in ms, -1 when unreachable or not running.
func Ping(ip string, timeoutMs int) int {
	mu.Lock()
	d := cnt
	mu.Unlock()
	a, err := netip.ParseAddr(ip)
	if d == nil || err != nil {
		return -1
	}
	return d.ping(a, time.Duration(timeoutMs)*time.Millisecond)
}

// Start runs Nebula on a dup of tunFd (the caller keeps and closes its own fd). Any running instance is stopped
// first.
func Start(yamlConfig string, tunFd int) error {
	mu.Lock()
	defer mu.Unlock()
	stopLocked()
	l := logging.NewLogger(os.Stderr)
	c := config.NewC(l)
	if err := c.LoadString(yamlConfig); err != nil {
		return err
	}
	if err := logging.ApplyConfig(l, c); err != nil {
		return err
	}
	// dup shares the fd's non-blocking flag, which Nebula's shutdown relies on
	fd, err := syscall.Dup(tunFd)
	if err != nil {
		return err
	}
	opened := false // once the tun device exists, Nebula closes fd itself on failure and on Stop
	dev := func(c *config.C, l *slog.Logger, nets []netip.Prefix, r int) (overlay.Device, error) {
		opened = true
		d, err := overlay.NewFdDeviceFromConfig(&fd)(c, l, nets, r)
		if err != nil {
			return nil, err
		}
		cnt = newCounting(d)
		return cnt, nil
	}
	n, err := nebula.Main(c, false, "transwarp", l, dev)
	if err != nil {
		if !opened {
			syscall.Close(fd)
		}
		return err
	}
	if err := n.Start(); err != nil {
		n.Stop()
		return err
	}
	ctl, cfg = n, c
	return nil
}

// Stop shuts Nebula down; a no-op when it isn't running.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	stopLocked()
}

func stopLocked() {
	if ctl != nil {
		ctl.Stop()
		ctl, cfg, cnt = nil, nil, nil
	}
}

// Rebind re-opens the UDP socket and re-announces to the lighthouses after a network change.
func Rebind() {
	mu.Lock()
	defer mu.Unlock()
	if ctl != nil {
		ctl.RebindUDPServer()
	}
}

type host struct {
	VpnAddrs []netip.Addr `json:"vpn_addrs"`
	Name     string       `json:"name,omitempty"` // its certificate name (gofire: node or client name)
	Remote   string       `json:"remote,omitempty"`
	Relays   []netip.Addr `json:"relays,omitempty"`
	RxBytes  int64        `json:"rx_bytes"`
	TxBytes  int64        `json:"tx_bytes"`
	LastRx   int64        `json:"last_rx,omitempty"` // unix seconds of the newest packet from it; 0 = none yet
}

type lighthouse struct {
	IP        string `json:"ip"`
	Reachable bool   `json:"reachable"`
}

type status struct {
	Running      bool         `json:"running"`
	AmLighthouse bool         `json:"am_lighthouse"`
	ListenPort   int          `json:"listen_port"`
	Lighthouses  []lighthouse `json:"lighthouses"`
	Hosts        []host       `json:"hosts"`
}

// Status is JSON: running, am_lighthouse, listen_port, lighthouses (static_host_map entries, reachable = an
// established tunnel to it), hosts (established tunnels with their current remote or relays).
func Status() string {
	mu.Lock()
	defer mu.Unlock()
	s := status{Lighthouses: []lighthouse{}, Hosts: []host{}}
	if ctl != nil {
		s.Running = true
		s.AmLighthouse = cfg.GetBool("lighthouse.am_lighthouse", false)
		s.ListenPort = cfg.GetInt("listen.port", 0)
		up := map[netip.Addr]bool{}
		for _, h := range ctl.ListHostmapHosts(false) {
			e := host{VpnAddrs: h.VpnAddrs, Relays: h.CurrentRelaysToMe}
			if h.CurrentRemote.IsValid() {
				e.Remote = h.CurrentRemote.String()
			}
			if h.Cert != nil {
				e.Name = h.Cert.Name()
			}
			for _, a := range h.VpnAddrs {
				up[a] = true
				if v, ok := cnt.peers.Load(a); ok {
					p := v.(*peerCtr)
					e.RxBytes, e.TxBytes, e.LastRx = e.RxBytes+p.rx.Load(), e.TxBytes+p.tx.Load(), max(e.LastRx, p.lastRx.Load())
				}
			}
			s.Hosts = append(s.Hosts, e)
		}
		for ip := range cfg.GetMap("static_host_map", nil) {
			a, err := netip.ParseAddr(ip)
			s.Lighthouses = append(s.Lighthouses, lighthouse{IP: ip, Reachable: err == nil && up[a]})
		}
	}
	b, _ := json.Marshal(s) // plain structs: can't fail
	return string(b)
}
