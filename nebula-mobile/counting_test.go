package nebmobile

import (
	"net/netip"
	"testing"
	"time"

	"github.com/slackhq/nebula/overlay"
	"github.com/slackhq/nebula/routing"
)

type fakeDev struct{ overlay.Device }

func (fakeDev) Networks() []netip.Prefix {
	return []netip.Prefix{netip.MustParsePrefix("10.90.200.7/16")}
}
func (fakeDev) RoutesFor(a netip.Addr) routing.Gateways {
	if netip.MustParsePrefix("10.89.0.0/16").Contains(a) {
		return routing.Gateways{routing.NewGateway(netip.MustParseAddr("10.90.0.1"), 1)}
	}
	return nil
}
func (fakeDev) Write(b []byte) (int, error) { return len(b), nil }
func (fakeDev) Read([]byte) (int, error)    { select {} } // an idle tun

func pkt(src, dst string, n int) []byte {
	b := make([]byte, n)
	b[0] = 0x45
	s, d := netip.MustParseAddr(src).As4(), netip.MustParseAddr(dst).As4()
	copy(b[12:], s[:])
	copy(b[16:], d[:])
	return b
}

// rx from a mesh host behind the hub counts for the hub; a Nebula peer counts for itself; v6 and off-route don't.
func TestCountingPerHost(t *testing.T) {
	d := newCounting(fakeDev{})
	d.Write(pkt("10.89.0.2", "10.90.200.7", 100))
	d.Write(pkt("10.90.200.4", "10.90.200.7", 60))
	d.Write(pkt("8.8.8.8", "10.90.200.7", 40))
	v6 := make([]byte, 40)
	v6[0] = 0x60
	d.Write(v6)
	get := func(a string) int64 {
		v, ok := d.peers.Load(netip.MustParseAddr(a))
		if !ok {
			return -1
		}
		return v.(*peerCtr).rx.Load()
	}
	if get("10.90.0.1") != 100 || get("10.90.200.4") != 60 || get("8.8.8.8") != -1 {
		t.Fatalf("hub=%d peer=%d other=%d", get("10.90.0.1"), get("10.90.200.4"), get("8.8.8.8"))
	}
}

// an injected echo request comes out of Read; its reply is swallowed by Write and ends the ping
func TestPingThroughNebula(t *testing.T) {
	d := newCounting(fakeDev{})
	done := make(chan int)
	go func() { done <- d.ping(netip.MustParseAddr("10.90.0.1"), time.Second) }()
	b := make([]byte, 1500)
	n, _ := d.Read(b)
	req := b[:n]
	if checksum(req[:20]) != 0 || checksum(req[20:]) != 0 || req[20] != 8 {
		t.Fatalf("bad request % x", req)
	}
	rep := append([]byte{}, req...)
	copy(rep[12:16], req[16:20])
	copy(rep[16:20], req[12:16])
	rep[20] = 0
	if m, _ := d.Write(rep); m != len(rep) {
		t.Fatal("reply not consumed")
	}
	if rtt := <-done; rtt < 0 {
		t.Fatal("ping timed out")
	}
	if d.ping(netip.MustParseAddr("10.90.0.9"), 50*time.Millisecond) != -1 {
		t.Fatal("unanswered ping should time out")
	}
}
