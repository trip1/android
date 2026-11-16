// Package nebmobile is Transwarp's gomobile binding around slackhq/nebula: one Nebula instance per process, fed a
// YAML config and the VpnService tun fd. Logs go to stderr, which gomobile pipes to logcat (tag GoLog).
//
// Rebuild the AAR (needs Go 1.26 + NDK): ./nebula-mobile/build.sh
package nebmobile

import (
	"encoding/json"
	"log/slog"
	"net/netip"
	"os"
	"sync"
	"syscall"

	"github.com/slackhq/nebula"
	"github.com/slackhq/nebula/config"
	"github.com/slackhq/nebula/logging"
	"github.com/slackhq/nebula/overlay"
)

var (
	mu  sync.Mutex
	ctl *nebula.Control
	cfg *config.C
)

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
		return overlay.NewFdDeviceFromConfig(&fd)(c, l, nets, r)
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
		ctl, cfg = nil, nil
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
	Remote   string       `json:"remote,omitempty"`
	Relays   []netip.Addr `json:"relays,omitempty"`
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
			for _, a := range h.VpnAddrs {
				up[a] = true
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
