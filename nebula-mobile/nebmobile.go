// Package nebmobile is Transwarp's gomobile binding around slackhq/nebula: one Nebula instance per process, fed a
// YAML config and the VpnService tun fd. Logs go to stderr, which gomobile pipes to logcat (tag GoLog).
//
// Rebuild the AAR (needs Go 1.27 + NDK): ./nebula-mobile/build.sh
package nebmobile

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/netip"
	"os"
	"reflect"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"

	"github.com/slackhq/nebula"
	"github.com/slackhq/nebula/config"
	"github.com/slackhq/nebula/logging"
	"github.com/slackhq/nebula/overlay"
	"github.com/slackhq/nebula/overlay/tio"
	"github.com/slackhq/nebula/udp"
)

var (
	mu  sync.Mutex
	ctl *nebula.Control
	cfg *config.C
	cnt *counting
)

// counting wraps the tun device and counts bytes per Nebula host, like WireGuard's per-peer rx/tx: a packet for an
// address in a Nebula network is that host's, one for an unsafe route is its gateway's (the hub).
type counting struct {
	overlay.Device
	peers sync.Map // netip.Addr -> *peerCtr

	// Ping: our app is off the VPN (Nebula's socket must be), so its pings can't use the tun. Echo requests are
	// fed to Nebula as if read from the tun; the matching replies are caught in Write and never reach the OS.
	inject  chan []byte
	waiting sync.Map // ICMP echo id (uint16) -> chan struct{}
	done    chan struct{}
	once    sync.Once
}

type peerCtr struct{ rx, tx, lastRx atomic.Int64 }

// peer is the counter of the packet's source (src) or destination host; nil for non-IP or off-route packets.
func (d *counting) peer(b []byte, src bool) *peerCtr {
	var a netip.Addr
	switch {
	case len(b) >= 20 && b[0]>>4 == 4:
		off := 16
		if src {
			off = 12
		}
		a = netip.AddrFrom4([4]byte(b[off : off+4]))
	case len(b) >= 40 && b[0]>>4 == 6:
		off := 24
		if src {
			off = 8
		}
		a = netip.AddrFrom16([16]byte(b[off : off+16]))
	default:
		return nil
	}
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

// Queues wraps the device's queue (Android has exactly one) so reads/writes are counted and pings injected.
func (d *counting) Queues(n int) ([]tio.Queue, error) {
	qs, err := d.Device.Queues(n)
	if err != nil || len(qs) == 0 {
		return qs, err
	}
	// ponytail: only the first queue carries pings; Android never opens more than one
	for i := range qs {
		qs[i] = &countingQueue{Queue: qs[i], d: d, ping: i == 0, rd: make(chan readResult, 1), ack: make(chan struct{})}
	}
	return qs, nil
}

func (d *counting) Close() error {
	d.once.Do(func() { close(d.done) })
	return d.Device.Close()
}

type readResult struct {
	pkts []tio.Packet
	err  error
}

type countingQueue struct {
	tio.Queue
	d    *counting
	ping bool
	pump sync.Once
	rd   chan readResult
	ack  chan struct{} // hands the inner queue's borrowed buffer back on the next Read
	held bool
	ret  [1]tio.Packet
}

// Read is packets leaving this device (tx to their destination host), or an injected ping. A pump goroutine reads
// the inner queue so Read can also select on injected packets.
func (q *countingQueue) Read() ([]tio.Packet, error) {
	if !q.ping {
		pkts, err := q.Queue.Read()
		q.countTx(pkts)
		return pkts, err
	}
	if q.held { // the caller is done with the previous batch (tio.Queue contract)
		q.held = false
		select {
		case q.ack <- struct{}{}:
		case <-q.d.done:
			return nil, os.ErrClosed
		}
	}
	q.pump.Do(func() {
		go func() {
			for {
				pkts, err := q.Queue.Read()
				q.rd <- readResult{pkts, err} // buffered: the last send after Stop doesn't block
				if err != nil {
					return
				}
				select {
				case <-q.ack:
				case <-q.d.done:
					return
				}
			}
		}()
	})
	select {
	case p := <-q.d.inject:
		q.ret[0] = tio.Packet{Bytes: p}
		return q.ret[:], nil
	case r := <-q.rd:
		if r.err != nil {
			return nil, r.err
		}
		q.held = true
		q.countTx(r.pkts)
		return r.pkts, nil
	}
}

func (q *countingQueue) countTx(pkts []tio.Packet) {
	for _, p := range pkts {
		if c := q.d.peer(p.Bytes, false); c != nil {
			c.tx.Add(int64(len(p.Bytes)))
		}
	}
}

// Write is a packet arriving from a host (rx from its source).
func (q *countingQueue) Write(b []byte) (int, error) {
	if p := q.d.peer(b, true); p != nil {
		p.rx.Add(int64(len(b)))
		p.lastRx.Store(time.Now().Unix())
	}
	if id, ok := echoReplyID(b); ok {
		if w, ok := q.d.waiting.LoadAndDelete(id); ok {
			close(w.(chan struct{}))
			return len(b), nil
		}
	}
	return q.Queue.Write(b)
}

func newCounting(dev overlay.Device) *counting {
	return &counting{Device: dev, inject: make(chan []byte, 4), done: make(chan struct{})}
}

// ping sends one ICMP echo through Nebula and waits for its reply: RTT in ms, -1 on timeout.
func (d *counting) ping(dst netip.Addr, timeout time.Duration) int {
	var src netip.Addr
	for _, n := range d.Networks() {
		if n.Addr().Is4() == dst.Is4() && !src.IsValid() {
			src = n.Addr()
		}
	}
	if !src.IsValid() {
		return -1
	}
	id := uint16(time.Now().UnixNano())
	w := make(chan struct{})
	d.waiting.Store(id, w)
	defer d.waiting.Delete(id)
	start := time.Now()
	select {
	case d.inject <- echoRequest(src, dst, id):
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

// echoRequest is an ICMP (v4, 28 bytes) or ICMPv6 (48 bytes) echo request (id, seq 1) from src to dst.
func echoRequest(src, dst netip.Addr, id uint16) []byte {
	if dst.Is6() {
		b := make([]byte, 48)
		b[0], b[5], b[6], b[7] = 0x60, 8, 58, 64 // v6, payload length 8, next header ICMPv6, hop limit 64
		s, t := src.As16(), dst.As16()
		copy(b[8:24], s[:])
		copy(b[24:40], t[:])
		b[40] = 128 // echo request
		binary.BigEndian.PutUint16(b[44:], id)
		binary.BigEndian.PutUint16(b[46:], 1)
		binary.BigEndian.PutUint16(b[42:], checksum6(b))
		return b
	}
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

// checksum6 is the ICMPv6 checksum of a v6 packet without extension headers (pseudo-header + ICMPv6 message).
func checksum6(b []byte) uint16 {
	ps := make([]byte, 40, 40+len(b)-40)
	copy(ps, b[8:40])
	binary.BigEndian.PutUint32(ps[32:], uint32(len(b)-40))
	ps[39] = 58
	return checksum(append(ps, b[40:]...))
}

func echoReplyID(b []byte) (uint16, bool) {
	if len(b) >= 48 && b[0]>>4 == 6 && b[6] == 58 && b[40] == 129 {
		return binary.BigEndian.Uint16(b[44:]), true
	}
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

// Protector is VpnService.protect: it keeps Nebula's own UDP socket off the VPN, so the rest of the app (directory,
// Beam, renew) can use the tunnel instead of being excluded from it.
type Protector interface {
	Protect(fd int) bool
}

// protectUDP hands every Nebula UDP socket to p before any packet is sent (Android's Rebind is a no-op, so the
// socket never changes). ponytail: reads nebula's unexported Control.f.writers (v1.12.0); Start fails loudly if a
// nebula upgrade moves it.
func protectUDP(n *nebula.Control, p Protector) error {
	f := reflect.ValueOf(n).Elem().FieldByName("f")
	if !f.IsValid() || f.IsNil() {
		return errors.New("nebula internals changed: no Control.f")
	}
	w := f.Elem().FieldByName("writers")
	if !w.IsValid() {
		return errors.New("nebula internals changed: no Interface.writers")
	}
	conns, _ := reflect.NewAt(w.Type(), unsafe.Pointer(w.UnsafeAddr())).Elem().Interface().([]udp.Conn)
	if len(conns) == 0 {
		return errors.New("nebula has no UDP socket to protect")
	}
	for _, c := range conns {
		sc, ok := c.(syscall.Conn)
		if !ok {
			return fmt.Errorf("nebula UDP conn %T has no fd", c)
		}
		rc, err := sc.SyscallConn()
		if err != nil {
			return err
		}
		ok = false
		if err := rc.Control(func(fd uintptr) { ok = p.Protect(int(fd)) }); err != nil {
			return err
		}
		if !ok {
			return errors.New("VpnService.protect failed for nebula's UDP socket")
		}
	}
	return nil
}

// Start runs Nebula on a dup of tunFd (the caller keeps and closes its own fd). Any running instance is stopped
// first. p protects Nebula's UDP socket from the VPN before it sends anything.
func Start(yamlConfig string, tunFd int, p Protector) error {
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
	if err := protectUDP(n, p); err != nil {
		n.Stop()
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

// Reload applies a new config to the running instance without dropping the tunnel (a renewed cert, new routes or
// firewall rules). Keys that need a restart (listen port, role) are reported as an error; the caller restarts.
func Reload(yamlConfig string) error {
	mu.Lock()
	defer mu.Unlock()
	if ctl == nil {
		return errors.New("nebula is not running")
	}
	r := cfg.ReloadConfigString(yamlConfig)
	switch {
	case r.Err != nil:
		return r.Err
	case r.Failed:
		return errors.New("nebula reload failed")
	case len(r.RestartKeys) > 0:
		return fmt.Errorf("nebula reload needs a restart for %v", r.RestartKeys)
	}
	return nil
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
