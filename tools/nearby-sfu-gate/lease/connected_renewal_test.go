package lease

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

func TestConnectedRenewalCannotExtendRegisteredOrUnconfirmedTransport(t *testing.T) {
	for _, state := range []string{"", "JOINING", "JOINED", "DISCONNECTED"} {
		g, now := fixture(8)
		if err := g.Register(ctx, key, 10*time.Second); err != nil {
			t.Fatal(err)
		}
		if state != "" {
			var closed atomic.Int32
			if err := g.Admit(ctx, key, func(context.Context) (Handle, error) {
				return Handle{ID: "p1", Close: func(context.Context) error { closed.Add(1); return nil },
					Observe: func() ParticipantObservation { return ParticipantObservation{SID: "p1", State: state} }}, nil
			}); err != nil {
				t.Fatal(err)
			}
		}
		*now = now.Add(5 * time.Second)
		if err := g.RenewConnected(ctx, key, "p1", 30*time.Second); !errors.Is(err, ErrDenied) {
			t.Fatalf("unconfirmed %q renewed: %v", state, err)
		}
		remaining, err := g.Remaining(ctx, key)
		if err != nil || remaining != 5*time.Second {
			t.Fatal("failed renewal extended admission window")
		}
	}
}

func TestConnectedRenewalRequiresCurrentActiveSIDInsideLeaseLock(t *testing.T) {
	g, now := fixture(8)
	if err := g.Register(ctx, key, 10*time.Second); err != nil {
		t.Fatal(err)
	}
	var closed atomic.Int32
	for _, sid := range []string{"p1", "p2"} {
		sid := sid
		if err := g.Admit(ctx, key, func(context.Context) (Handle, error) {
			return Handle{ID: sid, Close: func(context.Context) error { closed.Add(1); return nil },
				Observe: func() ParticipantObservation { return ParticipantObservation{SID: sid, State: "ACTIVE"} }}, nil
		}); err != nil {
			t.Fatal(err)
		}
	}
	*now = now.Add(5 * time.Second)
	if err := g.RenewConnected(ctx, key, "p1", 30*time.Second); !errors.Is(err, ErrDenied) {
		t.Fatal("old observed SID renewed replacement")
	}
	remaining, _ := g.Remaining(ctx, key)
	if remaining != 5*time.Second {
		t.Fatal("stale SID extended lease")
	}
	if err := g.RenewConnected(ctx, key, "p2", 30*time.Second); err != nil {
		t.Fatal(err)
	}
	remaining, _ = g.Remaining(ctx, key)
	if remaining != 30*time.Second {
		t.Fatal("active SID did not receive issued renewal")
	}
	*now = now.Add(30 * time.Second)
	if err := g.RenewConnected(ctx, key, "p2", 30*time.Second); !errors.Is(err, ErrDenied) {
		t.Fatal("expired media resurrected")
	}
}

func TestConnectedRenewalRechecksDeadlineAfterLocalObservation(t *testing.T) {
	g, now := fixture(8)
	if err := g.Register(ctx, key, 10*time.Second); err != nil {
		t.Fatal(err)
	}
	if err := g.Admit(ctx, key, func(context.Context) (Handle, error) {
		return Handle{ID: "p1", Close: func(context.Context) error { return nil }, Observe: func() ParticipantObservation {
			*now = now.Add(10 * time.Second)
			return ParticipantObservation{SID: "p1", State: "ACTIVE"}
		}}, nil
	}); err != nil {
		t.Fatal(err)
	}
	if err := g.RenewConnected(ctx, key, "p1", 30*time.Second); !errors.Is(err, ErrDenied) {
		t.Fatal("lookup completion resurrected expired admission")
	}
}
