package lease

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

var ctx = context.Background()
var key = Key{Room: "epoch", Identity: "session"}

func fixture(capacity int) (*Gate, *time.Time) {
	now := time.Now()
	return New(capacity, 30*time.Second, func() time.Time { return now }), &now
}
func join(counter *atomic.Int32, id string) Join {
	return func(context.Context) (Handle, error) {
		return Handle{ID: id, Close: func(context.Context) error { counter.Add(1); return nil }}, nil
	}
}
func TestUnknownAndRestartDenyAdmission(t *testing.T) {
	g, _ := fixture(8)
	issued := false
	if g.Admit(ctx, key, func(context.Context) (Handle, error) { issued = true; return Handle{}, nil }) == nil {
		t.Fatal("unknown admitted")
	}
	if issued {
		t.Fatal("join issued before register")
	}
	if err := g.Register(ctx, key, 10*time.Second); err != nil {
		t.Fatal(err)
	}
	fresh, _ := fixture(8)
	if fresh.Admit(ctx, key, nil) == nil {
		t.Fatal("restart admitted old credential")
	}
}
func TestRevokeSerializesWithPendingAdmissionAndClosesBeforeAck(t *testing.T) {
	g, _ := fixture(8)
	if err := g.Register(ctx, key, 10*time.Second); err != nil {
		t.Fatal(err)
	}
	entered, release := make(chan struct{}), make(chan struct{})
	var closed atomic.Int32
	joined := make(chan error, 1)
	go func() {
		joined <- g.Admit(ctx, key, func(context.Context) (Handle, error) {
			close(entered)
			<-release
			return Handle{ID: "p1", Close: func(context.Context) error { closed.Add(1); return nil }}, nil
		})
	}()
	<-entered
	// An already-cancelled revoke cannot acquire the pending admission lock.
	cancelled, cancel := context.WithCancel(ctx)
	cancel()
	if !errors.Is(g.Revoke(cancelled, key), context.Canceled) {
		t.Fatal("did not honor pending lock cancellation")
	}
	if closed.Load() != 0 {
		t.Fatal("closed unfinished admission")
	}
	close(release)
	if err := <-joined; err != nil {
		t.Fatal(err)
	}
	if err := g.Revoke(ctx, key); err != nil {
		t.Fatal(err)
	}
	if closed.Load() != 1 {
		t.Fatal("revoke acknowledged without close")
	}
	issued := false
	if !errors.Is(g.Admit(ctx, key, func(context.Context) (Handle, error) {
		issued = true
		return Handle{ID: "forbidden", Close: func(context.Context) error { return nil }}, nil
	}), ErrDenied) || issued {
		t.Fatal("revoked token reached join")
	}
	if g.Register(ctx, key, 10*time.Second) == nil || g.Renew(ctx, key, 10*time.Second) == nil {
		t.Fatal("revoked lease resurrected")
	}
}
func TestCancelBeforeRegisterCreatesTerminalTombstone(t *testing.T) {
	g, _ := fixture(8)
	if err := g.Revoke(ctx, key); err != nil {
		t.Fatal(err)
	}
	if g.Register(ctx, key, 10*time.Second) == nil {
		t.Fatal("late register resurrected cancelled session")
	}
}
func TestCleanupFailureRetainedForRetry(t *testing.T) {
	g, _ := fixture(8)
	_ = g.Register(ctx, key, 10*time.Second)
	attempts := 0
	if err := g.Admit(ctx, key, func(context.Context) (Handle, error) {
		return Handle{ID: "p1", Close: func(context.Context) error {
			attempts++
			if attempts == 1 {
				return errors.New("close failed")
			}
			return nil
		}}, nil
	}); err != nil {
		t.Fatal(err)
	}
	if g.Revoke(ctx, key) == nil {
		t.Fatal("cleanup failure acknowledged")
	}
	if g.Admit(ctx, key, nil) == nil {
		t.Fatal("cleanup failure reopened admission")
	}
	if err := g.Revoke(ctx, key); err != nil {
		t.Fatal(err)
	}
	if attempts != 2 {
		t.Fatalf("cleanup attempts=%d", attempts)
	}
}
func TestExpirySweepClosesMediaAndLateRenewCannotResurrect(t *testing.T) {
	g, now := fixture(8)
	var closed atomic.Int32
	_ = g.Register(ctx, key, 10*time.Second)
	if err := g.Admit(ctx, key, join(&closed, "p1")); err != nil {
		t.Fatal(err)
	}
	*now = now.Add(10 * time.Second)
	if err := g.Sweep(ctx); err != nil {
		t.Fatal(err)
	}
	if closed.Load() != 1 {
		t.Fatal("expiry did not close media")
	}
	if g.Renew(ctx, key, 10*time.Second) == nil || g.Admit(ctx, key, nil) == nil {
		t.Fatal("expiry resurrected")
	}
}
func TestCapacityRetainsTombstonesAndRejectsNewRegistration(t *testing.T) {
	g, _ := fixture(1)
	_ = g.Revoke(ctx, key)
	other := Key{Room: "epoch", Identity: "other"}
	if g.Register(ctx, other, 10*time.Second) == nil {
		t.Fatal("full registry accepted new key")
	}
	if g.Register(ctx, key, 10*time.Second) == nil {
		t.Fatal("tombstone evicted")
	}
}

func TestDeadlineEnforcedBeforeSweep(t *testing.T) {
	for _, operation := range []string{"register", "renew", "admit"} {
		t.Run(operation, func(t *testing.T) {
			g, now := fixture(8)
			if err := g.Register(ctx, key, 10*time.Second); err != nil {
				t.Fatal(err)
			}
			*now = now.Add(10 * time.Second)
			issued := false
			var err error
			switch operation {
			case "register":
				err = g.Register(ctx, key, 10*time.Second)
			case "renew":
				err = g.Renew(ctx, key, 10*time.Second)
			case "admit":
				err = g.Admit(ctx, key, func(context.Context) (Handle, error) {
					issued = true
					return Handle{ID: "forbidden", Close: func(context.Context) error { return nil }}, nil
				})
			}
			if !errors.Is(err, ErrDenied) || issued {
				t.Fatal("deadline not enforced before operation")
			}
		})
	}
}
func TestRenewAndDuplicateRegisterHaveDifferentDeadlineSemantics(t *testing.T) {
	g, now := fixture(8)
	_ = g.Register(ctx, key, 10*time.Second)
	*now = now.Add(9 * time.Second)
	_ = g.Register(ctx, key, 30*time.Second)
	*now = now.Add(time.Second)
	if g.Renew(ctx, key, 10*time.Second) == nil {
		t.Fatal("duplicate register extended original deadline")
	}
	other := Key{Room: "epoch", Identity: "other"}
	_ = g.Register(ctx, other, 10*time.Second)
	*now = now.Add(9 * time.Second)
	if err := g.Renew(ctx, other, 10*time.Second); err != nil {
		t.Fatal(err)
	}
	*now = now.Add(time.Second)
	var closed atomic.Int32
	if err := g.Admit(ctx, other, join(&closed, "p2")); err != nil {
		t.Fatal("valid renewal not honored", err)
	}
}
func TestAdmissionCompletingAfterDeadlineIsClosedAndDenied(t *testing.T) {
	g, now := fixture(8)
	_ = g.Register(ctx, key, 10*time.Second)
	var closed atomic.Int32
	err := g.Admit(ctx, key, func(context.Context) (Handle, error) {
		*now = now.Add(10 * time.Second)
		return Handle{ID: "p1", Close: func(context.Context) error { closed.Add(1); return nil }}, nil
	})
	if err == nil || closed.Load() != 1 {
		t.Fatal("late admission not closed")
	}
}
func TestReplacementClosesOldHandleAndResumeKeepsSameTransport(t *testing.T) {
	g, _ := fixture(8)
	_ = g.Register(ctx, key, 10*time.Second)
	var closed atomic.Int32
	_ = g.Admit(ctx, key, join(&closed, "p1"))
	_ = g.Admit(ctx, key, join(&closed, "p1"))
	if closed.Load() != 0 {
		t.Fatal("resume closed live transport")
	}
	_ = g.Admit(ctx, key, join(&closed, "p2"))
	if closed.Load() != 1 {
		t.Fatal("replacement left old transport open")
	}
	_ = g.Revoke(ctx, key)
	if closed.Load() != 2 {
		t.Fatal("replacement not closed on revoke")
	}
}
