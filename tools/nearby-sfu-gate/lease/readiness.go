package lease

import (
	"encoding/hex"
	"io"
	"sync"
	"time"
)

func NewNodeIncarnation(entropy io.Reader) (string, error) {
	var nonce [16]byte
	if _, err := io.ReadFull(entropy, nonce[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(nonce[:]), nil
}

type RegistryStats struct{ Used, Capacity int }

func (g *Gate) RegistryStats() RegistryStats {
	g.mu.Lock()
	defer g.mu.Unlock()
	return RegistryStats{Used: len(g.entries), Capacity: g.capacity}
}

type SweepStatus struct {
	Ready     bool
	AgeMillis int64
}
type SweepHealth struct {
	mu               sync.Mutex
	now              func() time.Time
	maximumAge       time.Duration
	lastSuccess      time.Time
	observed, failed bool
}

func NewSweepHealth(maximumAge time.Duration, now func() time.Time) *SweepHealth {
	if maximumAge <= 0 || now == nil {
		panic("invalid sweep health configuration")
	}
	return &SweepHealth{maximumAge: maximumAge, now: now}
}
func (h *SweepHealth) Observe(err error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.failed = err != nil
	if err == nil {
		h.lastSuccess = h.now()
		h.observed = true
	}
}
func (h *SweepHealth) Status() SweepStatus {
	h.mu.Lock()
	defer h.mu.Unlock()
	if !h.observed {
		return SweepStatus{AgeMillis: -1}
	}
	age := h.now().Sub(h.lastSuccess)
	if age < 0 {
		return SweepStatus{AgeMillis: -1}
	}
	return SweepStatus{Ready: !h.failed && age < h.maximumAge, AgeMillis: age.Milliseconds()}
}
