// Volty single-node admission experiment; not a production LiveKit extension.
package service

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"sync"
	"time"

	"github.com/livekit/livekit-server/pkg/routing"
	"github.com/livekit/livekit-server/pkg/rtc/types"
	"github.com/livekit/livekit-server/pkg/voltylease"
	"github.com/livekit/protocol/livekit"
)

var voltyGate = lease.New(4096, 30*time.Second, time.Now)
var voltySweep sync.Once

func (s *RTCService) setupVoltyLeases(mux *http.ServeMux) {
	if s.config.Redis.IsConfigured() {
		panic("volty prototype requires single-node SFU without Redis")
	}
	mux.HandleFunc("/volty/leases/register", s.voltyLeaseControl)
	mux.HandleFunc("/volty/leases/renew", s.voltyLeaseControl)
	mux.HandleFunc("/volty/leases/revoke", s.voltyLeaseControl)
	for _, action := range []string{"arm", "entered", "release", "revoking"} {
		mux.HandleFunc("/volty/test/barrier/"+action, s.voltyBarrierControl)
	}
	voltySweep.Do(func() {
		go func() {
			ticker := time.NewTicker(250 * time.Millisecond)
			defer ticker.Stop()
			for range ticker.C {
				ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
				_ = voltyGate.Sweep(ctx)
				cancel()
			}
		}()
	})
}
func (s *RTCService) voltyLeaseControl(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method_denied", 405)
		return
	}
	var body struct {
		Room       string `json:"room"`
		Identity   string `json:"identity"`
		TTLSeconds int64  `json:"ttlSeconds"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&body) != nil {
		http.Error(w, "lease_invalid", 400)
		return
	}
	var trailing any
	if decoder.Decode(&trailing) != io.EOF {
		http.Error(w, "lease_invalid", 400)
		return
	}
	if EnsureAdminPermission(r.Context(), livekit.RoomName(body.Room)) != nil {
		http.Error(w, "lease_forbidden", 403)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	key := lease.Key{Room: body.Room, Identity: body.Identity}
	var err error
	if r.URL.Path == "/volty/leases/revoke" {
		voltyMarkRevoking(key)
		err = voltyGate.Revoke(ctx, key)
	} else {
		if body.TTLSeconds < 1 || body.TTLSeconds > 30 {
			http.Error(w, "lease_invalid", 400)
			return
		}
		ttl := time.Duration(body.TTLSeconds) * time.Second
		if r.URL.Path == "/volty/leases/register" {
			err = voltyGate.Register(ctx, key, ttl)
		} else {
			err = voltyGate.Renew(ctx, key, ttl)
		}
	}
	if err != nil {
		http.Error(w, "lease_rejected", 409)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (r *RoomManager) StartSession(ctx context.Context, pi routing.ParticipantInit, source routing.MessageSource, sink routing.MessageSink, oneShot bool) error {
	if pi.Identity == "" {
		return r.startSessionUnguarded(ctx, pi, source, sink, oneShot, func(types.LocalParticipant) {})
	}
	if pi.CreateRoom == nil {
		return lease.ErrInvalid
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	key := lease.Key{Room: pi.CreateRoom.Name, Identity: string(pi.Identity)}
	return voltyGate.Admit(ctx, key, func(ctx context.Context) (lease.Handle, error) {
		if err := voltyWaitBarrier(ctx, key); err != nil {
			return lease.Handle{}, err
		}
		var captured types.LocalParticipant
		err := r.startSessionUnguarded(ctx, pi, source, sink, oneShot, func(p types.LocalParticipant) { captured = p })
		if captured == nil {
			return lease.Handle{}, lease.ErrAdmission
		}
		handle := lease.Handle{ID: string(captured.ID()), Close: func(ctx context.Context) error {
			room := r.GetRoom(ctx, livekit.RoomName(key.Room))
			if room != nil {
				room.RemoveParticipant(captured.Identity(), captured.ID(), types.ParticipantCloseReasonServiceRequestRemoveParticipant)
			}
			_ = captured.Close(true, types.ParticipantCloseReasonServiceRequestRemoveParticipant, false)
			waiter, ok := captured.(interface{ VoltyWaitMediaClosed(context.Context) error })
			if !ok {
				return lease.ErrCleanup
			}
			return waiter.VoltyWaitMediaClosed(ctx)
		}}
		return handle, err
	})
}
