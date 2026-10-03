package lease

import (
	"context"
	"time"
)

// SFU state at the instant of a local lookup, not proof of device audibility.
type ParticipantObservation struct {
	SID   string `json:"sid"`
	State string `json:"state"`
}

func (g *Gate) Observe(ctx context.Context, key Key) (ParticipantObservation, error) {
	e, err := g.find(key, false)
	if err != nil {
		return ParticipantObservation{}, err
	}
	if err = e.lock(ctx); err != nil {
		return ParticipantObservation{}, err
	}
	defer e.unlock()
	return g.observeLocked(ctx, e)
}

func (g *Gate) observeLocked(ctx context.Context, e *entry) (ParticipantObservation, error) {
	if !g.active(e) {
		return ParticipantObservation{}, ErrDenied
	}
	value := ParticipantObservation{}
	for _, handle := range e.handles {
		if handle.Observe == nil {
			return ParticipantObservation{}, ErrInvalid
		}
		current := handle.Observe()
		if current == (ParticipantObservation{}) {
			continue
		}
		if current.SID != handle.ID || value.SID != "" {
			return ParticipantObservation{}, ErrInvalid
		}
		switch current.State {
		case "JOINING", "JOINED", "ACTIVE", "DISCONNECTED":
		default:
			return ParticipantObservation{}, ErrInvalid
		}
		value = current
	}
	if err := ctx.Err(); err != nil {
		return ParticipantObservation{}, err
	}
	if !g.active(e) {
		return ParticipantObservation{}, ErrDenied
	}
	return value, nil
}

// The lookup and renewal share admission/revoke's entry lock; no stale SID grant.
func (g *Gate) RenewConnected(ctx context.Context, key Key, expectedSID string, ttl time.Duration) error {
	if expectedSID == "" || len(expectedSID) > 256 || !g.validTTL(ttl) {
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
	current, err := g.observeLocked(ctx, e)
	if err != nil {
		return err
	}
	if current.SID != expectedSID || current.State != "ACTIVE" {
		return ErrDenied
	}
	e.deadline = g.now().Add(ttl)
	return nil
}
