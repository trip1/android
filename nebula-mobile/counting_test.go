package nebmobile

import (
	"net/netip"
	"testing"
	"time"

	"github.com/slackhq/nebula/overlay"
	"github.com/slackhq/nebula/overlay/tio"
	"github.com/slackhq/nebula/routing"
)

type fakeDev struct{ overlay.Device }

func (fakeDev) Networks() []netip.Prefix {
	return []netip.Prefix{netip.MustParsePrefix("10.90.200.7/16"), netip.MustParsePrefix("fdee:6e65:6275:6c61::c8:7/64")}
}
func (fakeDev) RoutesFor(a netip.Addr) routing.Gateways {
	if netip.MustParsePrefix("10.89.0.0/16").Contains(a) {
		return routing.Gateways{routing.NewGateway(netip.MustParseAddr("10.90.0.1"), 1)}
	}
	return nil
}
func (fakeDev) Queues(int) ([]tio.Queue, error) { return []tio.Queue{fakeQueue{}}, nil }
func (fakeDev) Close() error                    { return nil }

type fakeQueue struct{ tio.Queue }

func (fakeQueue) Write(b []byte) (int, error) { return len(b), nil }
func (fakeQueue) Read() ([]tio.Packet, error) { select {} } // an idle tun

func pkt(src, dst string, n int) []byte {
	s, d := netip.MustParseAddr(src), netip.MustParseAddr(dst)
	b := make([]byte, n)
	if s.Is6() {
		b[0] = 0x60
		s16, d16 := s.As16(), d.As16()
		copy(b[8:], s16[:])
		copy(b[24:], d16[:])
		return b
	}
	b[0] = 0x45
	s4, d4 := s.As4(), d.As4()
	copy(b[12:], s4[:])
	copy(b[16:], d4[:])
	return b
}

func queue(t *testing.T, d *counting) tio.Queue {
	qs, err := d.Queues(1)
	if err != nil || len(qs) != 1 {
		t.Fatal(err)
	}
	return qs[0]
}

// rx from a mesh host behind the hub counts for the hub; a Nebula peer counts for itself (v4 and v6); off-route doesn't.
func TestCountingPerHost(t *testing.T) {
	d := newCounting(fakeDev{})
	q := queue(t, d)
	q.Write(pkt("10.89.0.2", "10.90.200.7", 100))
	q.Write(pkt("10.90.200.4", "10.90.200.7", 60))
	q.Write(pkt("8.8.8.8", "10.90.200.7", 40))
	q.Write(pkt("fdee:6e65:6275:6c61::c8:4", "fdee:6e65:6275:6c61::c8:7", 80))
	q.Write(pkt("2001:db8::1", "fdee:6e65:6275:6c61::c8:7", 80))
	get := func(a string) int64 {
		v, ok := d.peers.Load(netip.MustParseAddr(a))
		if !ok {
			return -1
		}
		return v.(*peerCtr).rx.Load()
	}
	if get("10.90.0.1") != 100 || get("10.90.200.4") != 60 || get("8.8.8.8") != -1 ||
		get("fdee:6e65:6275:6c61::c8:4") != 80 || get("2001:db8::1") != -1 {
		t.Fatalf("hub=%d peer=%d other=%d v6=%d", get("10.90.0.1"), get("10.90.200.4"), get("8.8.8.8"), get("fdee:6e65:6275:6c61::c8:4"))
	}
}

// an injected echo request (v4 and v6) comes out of Read; its reply is swallowed by Write and ends the ping
func TestPingThroughNebula(t *testing.T) {
	d := newCounting(fakeDev{})
	q := queue(t, d)
	for _, dst := range []string{"10.90.0.1", "fdee:6e65:6275:6c61::0:1"} {
		done := make(chan int)
		go func() { done <- d.ping(netip.MustParseAddr(dst), time.Second) }()
		pkts, err := q.Read()
		if err != nil || len(pkts) != 1 {
			t.Fatal(err)
		}
		req := pkts[0].Bytes
		rep := append([]byte{}, req...)
		if req[0]>>4 == 4 {
			if checksum(req[:20]) != 0 || checksum(req[20:]) != 0 || req[20] != 8 {
				t.Fatalf("bad request % x", req)
			}
			copy(rep[12:16], req[16:20])
			copy(rep[16:20], req[12:16])
			rep[20] = 0
		} else {
			if req[40] != 128 || checksum6(req) != 0 || string(req[8:24]) != string(netip.MustParseAddr("fdee:6e65:6275:6c61::c8:7").AsSlice()) {
				t.Fatalf("bad v6 request % x", req)
			}
			rep[40] = 129
		}
		if m, _ := q.Write(rep); m != len(rep) {
			t.Fatal("reply not consumed")
		}
		if rtt := <-done; rtt < 0 {
			t.Fatalf("ping %s timed out", dst)
		}
	}
	if d.ping(netip.MustParseAddr("10.90.0.9"), 50*time.Millisecond) != -1 {
		t.Fatal("unanswered ping should time out")
	}
}
