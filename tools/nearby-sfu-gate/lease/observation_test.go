package lease

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

func TestObservationDoesNotInventMediaOrExtendLease(t *testing.T) {
	g, now := fixture(8)
	if err := g.Register(ctx, key, 10*time.Second); err != nil {
		t.Fatal(err)
	}
	value, err := g.Observe(ctx, key)
	if err != nil || value.SID != "" {
		t.Fatalf("registered is not connected: %+v %v", value, err)
	}
	*now = now.Add(10 * time.Second)
	if _, err := g.Observe(ctx, key); !errors.Is(err, ErrDenied) {
		t.Fatal("observation extended expired lease")
	}
}

func TestObservationTracksCurrentHandleAndNotPriorSID(t *testing.T) {
	g, _ := fixture(8)
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
		value, err := g.Observe(ctx, key)
		if err != nil || value.SID != sid || value.State != "ACTIVE" {
			t.Fatalf("wrong current transport: %+v %v", value, err)
		}
	}
	if closed.Load() != 1 {
		t.Fatal("old transport was not closed")
	}
	if err := g.Revoke(ctx, key); err != nil {
		t.Fatal(err)
	}
	if _, err := g.Observe(ctx, key); !errors.Is(err, ErrDenied) {
		t.Fatal("terminal observation accepted")
	}
}

func TestObservationRejectsMissingAndIncoherentHandleProvenance(t *testing.T) {
	for _, observe := range []func() ParticipantObservation{nil, func() ParticipantObservation {
		return ParticipantObservation{SID: "different", State: "ACTIVE"}
	}, func() ParticipantObservation { return ParticipantObservation{SID: "p1", State: "future-state"} }} {
		g, _ := fixture(8)
		if err := g.Register(ctx, key, 10*time.Second); err != nil {
			t.Fatal(err)
		}
		var closed atomic.Int32
		if err := g.Admit(ctx, key, func(context.Context) (Handle, error) {
			return Handle{ID: "p1", Close: func(context.Context) error { closed.Add(1); return nil }, Observe: observe}, nil
		}); err != nil {
			t.Fatal(err)
		}
		if _, err := g.Observe(ctx, key); !errors.Is(err, ErrInvalid) {
			t.Fatal("incoherent observation accepted")
		}
	}
}

func TestObservationWaitIsCancellableBehindIssuedAdmission(t *testing.T) {
	g, _ := fixture(8)
	if err := g.Register(ctx, key, 10*time.Second); err != nil {
		t.Fatal(err)
	}
	entered, release, done := make(chan struct{}), make(chan struct{}), make(chan error, 1)
	go func() {
		done <- g.Admit(ctx, key, func(context.Context) (Handle, error) {
			close(entered)
			<-release
			return Handle{ID: "p1", Close: func(context.Context) error { return nil }}, nil
		})
	}()
	<-entered
	cancelled, cancel := context.WithCancel(ctx)
	cancel()
	_, err := g.Observe(cancelled, key)
	close(release)
	if joined := <-done; joined != nil {
		t.Fatal(joined)
	}
	if !errors.Is(err, context.Canceled) {
		t.Fatal("pending gate ignored cancellation")
	}
}
