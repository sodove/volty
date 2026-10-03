// Package lease is a single-node prototype, never a distributed lease store.
package lease

import (
	"context"
	"errors"
	"sync"
	"time"
)

var (
	ErrDenied    = errors.New("lease_denied")
	ErrCapacity  = errors.New("lease_capacity")
	ErrInvalid   = errors.New("lease_invalid")
	ErrCleanup   = errors.New("lease_cleanup")
	ErrAdmission = errors.New("lease_admission")
)

type Key struct{ Room, Identity string }
type Handle struct {
	ID    string
	Close func(context.Context) error
}
type Join func(context.Context) (Handle, error)
type entry struct {
	serial               chan struct{}
	registered, terminal bool
	deadline             time.Time
	handles              map[string]Handle
}
type Gate struct {
	mu         sync.Mutex
	entries    map[Key]*entry
	capacity   int
	maximumTTL time.Duration
	now        func() time.Time
}

func New(capacity int, maximumTTL time.Duration, now func() time.Time) *Gate {
	if capacity <= 0 || maximumTTL <= 0 || now == nil {
		panic("invalid lease gate configuration")
	}
	return &Gate{entries: make(map[Key]*entry), capacity: capacity, maximumTTL: maximumTTL, now: now}
}
func (g *Gate) find(key Key, create bool) (*entry, error) {
	if key.Room == "" || key.Identity == "" || len(key.Room) > 256 || len(key.Identity) > 256 {
		return nil, ErrInvalid
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if e := g.entries[key]; e != nil {
		return e, nil
	}
	if !create {
		return nil, ErrDenied
	}
	if len(g.entries) >= g.capacity {
		return nil, ErrCapacity
	}
	e := &entry{serial: make(chan struct{}, 1), handles: make(map[string]Handle)}
	g.entries[key] = e
	return e, nil
}
func (e *entry) lock(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	select {
	case e.serial <- struct{}{}:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
func (e *entry) unlock()                        { <-e.serial }
func (g *Gate) validTTL(ttl time.Duration) bool { return ttl > 0 && ttl <= g.maximumTTL }
func (g *Gate) active(e *entry) bool {
	return e.registered && !e.terminal && g.now().Before(e.deadline)
}
func (e *entry) cleanup(ctx context.Context) error {
	failed := false
	for id, h := range e.handles {
		if h.Close(ctx) != nil {
			failed = true
		} else {
			delete(e.handles, id)
		}
	}
	if failed {
		return ErrCleanup
	}
	return nil
}
func (g *Gate) Register(ctx context.Context, key Key, ttl time.Duration) error {
	if !g.validTTL(ttl) {
		return ErrInvalid
	}
	e, err := g.find(key, true)
	if err != nil {
		return err
	}
	if err = e.lock(ctx); err != nil {
		return err
	}
	defer e.unlock()
	if e.terminal {
		return ErrDenied
	}
	if e.registered {
		if !g.active(e) {
			e.terminal = true
			_ = e.cleanup(ctx)
			return ErrDenied
		}
		return nil // Idempotent replay does not renew the lease.
	}
	e.registered = true
	e.deadline = g.now().Add(ttl)
	return nil
}
func (g *Gate) Renew(ctx context.Context, key Key, ttl time.Duration) error {
	if !g.validTTL(ttl) {
		return ErrInvalid
	}
	e, err := g.find(key, false)
	if err != nil {
		return err
	}
	if err = e.lock(ctx); err != nil {
		return err
	}
	defer e.unlock()
	if !g.active(e) {
		e.terminal = true
		_ = e.cleanup(ctx)
		return ErrDenied
	}
	e.deadline = g.now().Add(ttl)
	return nil
}
func (g *Gate) Admit(ctx context.Context, key Key, join Join) error {
	e, err := g.find(key, false)
	if err != nil {
		return err
	}
	if err = e.lock(ctx); err != nil {
		return err
	}
	defer e.unlock()
	if !g.active(e) {
		e.terminal = true
		_ = e.cleanup(ctx)
		return ErrDenied
	}
	if join == nil {
		return ErrInvalid
	}
	next, joined := join(ctx)
	if next.ID != "" && next.Close != nil {
		e.handles[next.ID] = next
	}
	if joined != nil || next.ID == "" || next.Close == nil || ctx.Err() != nil || !g.active(e) {
		e.terminal = true
		if e.cleanup(ctx) != nil {
			return ErrCleanup
		}
		return ErrAdmission
	}
	// Same SID is a signalling resume. A new SID must not leave its old transport alive.
	for id, old := range e.handles {
		if id == next.ID {
			continue
		}
		if old.Close(ctx) != nil {
			e.terminal = true
			_ = e.cleanup(ctx)
			return ErrCleanup
		}
		delete(e.handles, id)
	}
	return nil
}
func (g *Gate) Revoke(ctx context.Context, key Key) error {
	e, err := g.find(key, true)
	if err != nil {
		return err
	}
	if err = e.lock(ctx); err != nil {
		return err
	}
	defer e.unlock()
	e.terminal = true
	return e.cleanup(ctx)
}
func (g *Gate) Sweep(ctx context.Context) error {
	g.mu.Lock()
	entries := make([]*entry, 0, len(g.entries))
	for _, e := range g.entries {
		entries = append(entries, e)
	}
	g.mu.Unlock()
	var result error
	for _, e := range entries {
		if err := e.lock(ctx); err != nil {
			return err
		}
		if e.registered && !g.now().Before(e.deadline) {
			e.terminal = true
		}
		if e.terminal && e.cleanup(ctx) != nil {
			result = ErrCleanup
		}
		e.unlock()
	}
	return result
}
