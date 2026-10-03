package service

import (
	"context"
	"encoding/json"
	"github.com/livekit/livekit-server/pkg/voltylease"
	"github.com/livekit/protocol/livekit"
	"net"
	"net/http"
	"sync"
)

// Deterministic loopback test barrier, never part of production admission API.
type voltyBarrier struct{ entered, release, revoking chan struct{} }

var voltyBarriers = struct {
	sync.Mutex
	values map[lease.Key]*voltyBarrier
}{values: make(map[lease.Key]*voltyBarrier)}

func voltyMarkRevoking(key lease.Key) {
	voltyBarriers.Lock()
	defer voltyBarriers.Unlock()
	if b := voltyBarriers.values[key]; b != nil {
		select {
		case <-b.revoking:
		default:
			close(b.revoking)
		}
	}
}
func voltyWaitBarrier(ctx context.Context, key lease.Key) error {
	voltyBarriers.Lock()
	b := voltyBarriers.values[key]
	if b != nil {
		select {
		case <-b.entered:
		default:
			close(b.entered)
		}
	}
	voltyBarriers.Unlock()
	if b == nil {
		return nil
	}
	defer func() { voltyBarriers.Lock(); delete(voltyBarriers.values, key); voltyBarriers.Unlock() }()
	select {
	case <-b.release:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
func (s *RTCService) voltyBarrierControl(w http.ResponseWriter, r *http.Request) {
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	address := net.ParseIP(host)
	if address == nil || !address.IsLoopback() || r.Method != http.MethodPost {
		http.Error(w, "barrier_forbidden", 403)
		return
	}
	var body struct {
		Room     string `json:"room"`
		Identity string `json:"identity"`
	}
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&body) != nil || body.Room == "" || body.Identity == "" {
		http.Error(w, "barrier_invalid", 400)
		return
	}
	if EnsureAdminPermission(r.Context(), livekit.RoomName(body.Room)) != nil {
		http.Error(w, "barrier_forbidden", 403)
		return
	}
	key := lease.Key{Room: body.Room, Identity: body.Identity}
	voltyBarriers.Lock()
	defer voltyBarriers.Unlock()
	b := voltyBarriers.values[key]
	if r.URL.Path == "/volty/test/barrier/arm" {
		if b != nil || len(voltyBarriers.values) >= 32 {
			http.Error(w, "barrier_conflict", 409)
			return
		}
		voltyBarriers.values[key] = &voltyBarrier{entered: make(chan struct{}), release: make(chan struct{}), revoking: make(chan struct{})}
	} else {
		if b == nil {
			http.Error(w, "barrier_missing", 409)
			return
		}
		switch r.URL.Path {
		case "/volty/test/barrier/release":
			select {
			case <-b.release:
			default:
				close(b.release)
			}
		case "/volty/test/barrier/entered":
			select {
			case <-b.entered:
			default:
				http.Error(w, "barrier_waiting", 409)
				return
			}
		case "/volty/test/barrier/revoking":
			select {
			case <-b.revoking:
			default:
				http.Error(w, "barrier_waiting", 409)
				return
			}
		default:
			http.Error(w, "barrier_invalid", 400)
			return
		}
	}
	w.WriteHeader(204)
}
