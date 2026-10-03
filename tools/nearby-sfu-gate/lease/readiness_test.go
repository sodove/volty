package lease

import (
	"bytes"
	"errors"
	"io"
	"testing"
	"time"
)

func TestNodeIncarnationRequiresFreshCompleteEntropy(t *testing.T) {
	first, err := NewNodeIncarnation(bytes.NewReader(bytes.Repeat([]byte{1}, 16)))
	if err != nil {
		t.Fatal(err)
	}
	second, err := NewNodeIncarnation(bytes.NewReader(bytes.Repeat([]byte{2}, 16)))
	if err != nil || first == second || len(first) != 32 {
		t.Fatal("incarnation not distinct")
	}
	if _, err = NewNodeIncarnation(bytes.NewReader([]byte{1})); !errors.Is(err, io.ErrUnexpectedEOF) {
		t.Fatal("partial entropy accepted")
	}
}

func TestHealthUnknownAndExactDeadlineFailClosed(t *testing.T) {
	now := time.Unix(100, 0)
	h := NewSweepHealth(2*time.Second, func() time.Time { return now })
	if status := h.Status(); status.Ready || status.AgeMillis != -1 {
		t.Fatal("unknown reported healthy")
	}
	h.Observe(nil)
	if !h.Status().Ready {
		t.Fatal("completed sweep not observed")
	}
	now = now.Add(2 * time.Second)
	if h.Status().Ready {
		t.Fatal("stale sweep accepted at exact deadline")
	}
}

func TestSweepFailureBlocksUntilSuccessfulCleanup(t *testing.T) {
	now := time.Unix(100, 0)
	h := NewSweepHealth(2*time.Second, func() time.Time { return now })
	h.Observe(nil)
	h.Observe(ErrCleanup)
	if h.Status().Ready {
		t.Fatal("cleanup failure reported healthy")
	}
	now = now.Add(time.Second)
	h.Observe(nil)
	if !h.Status().Ready {
		t.Fatal("successful retry did not recover")
	}
}

func TestClockBeforeLastSweepIsNotInventedAsZeroAge(t *testing.T) {
	now := time.Unix(100, 0)
	h := NewSweepHealth(2*time.Second, func() time.Time { return now })
	h.Observe(nil)
	now = now.Add(-time.Second)
	if status := h.Status(); status.Ready || status.AgeMillis != -1 {
		t.Fatal("clock reversal reported healthy")
	}
}

func TestRegistryStatsCountTerminalTombstones(t *testing.T) {
	g, _ := fixture(1)
	if stats := g.RegistryStats(); stats.Used != 0 || stats.Capacity != 1 {
		t.Fatal("initial capacity wrong")
	}
	if err := g.Revoke(ctx, key); err != nil {
		t.Fatal(err)
	}
	if stats := g.RegistryStats(); stats.Used != 1 || stats.Capacity != 1 {
		t.Fatal("terminal capacity evicted")
	}
}

func TestRemainingLeaseIsNotResetByIdempotentRegisterReplay(t *testing.T) {
	g, now := fixture(2)
	if err := g.Register(ctx, key, 10*time.Second); err != nil {
		t.Fatal(err)
	}
	*now = now.Add(3 * time.Second)
	if err := g.Register(ctx, key, 30*time.Second); err != nil {
		t.Fatal(err)
	}
	remaining, err := g.Remaining(ctx, key)
	if err != nil || remaining != 7*time.Second {
		t.Fatalf("replay fabricated deadline: %v %v", remaining, err)
	}
	if err := g.Revoke(ctx, key); err != nil {
		t.Fatal(err)
	}
	if _, err := g.Remaining(ctx, key); err != ErrDenied {
		t.Fatal("terminal lease acknowledged")
	}
}
