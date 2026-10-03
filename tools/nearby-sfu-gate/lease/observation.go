package lease

import "context"

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
