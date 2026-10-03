package service

import (
	"crypto/rand"
	"encoding/json"
	"io"
	"net/http"
	"time"

	"github.com/livekit/livekit-server/pkg/voltylease"
	"github.com/livekit/protocol/livekit"
)

var voltyNodeEpoch = func() string {
	epoch, err := lease.NewNodeIncarnation(rand.Reader)
	if err != nil {
		panic("volty node entropy unavailable")
	}
	return epoch
}()
var voltyHealth = lease.NewSweepHealth(2*time.Second, time.Now)

func (s *RTCService) voltyReadiness(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method_denied", 405)
		return
	}
	var body struct {
		Room string `json:"room"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096))
	decoder.DisallowUnknownFields()
	var trailing any
	if decoder.Decode(&body) != nil || body.Room == "" || decoder.Decode(&trailing) != io.EOF {
		http.Error(w, "readiness_invalid", 400)
		return
	}
	if EnsureAdminPermission(r.Context(), livekit.RoomName(body.Room)) != nil {
		http.Error(w, "readiness_forbidden", 403)
		return
	}
	stats := voltyGate.RegistryStats()
	status := voltyHealth.Status()
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(struct {
		ProtocolVersion     int    `json:"protocolVersion"`
		NodeEpoch           string `json:"nodeEpoch"`
		Ready               bool   `json:"ready"`
		SlotsUsed           int    `json:"slotsUsed"`
		Capacity            int    `json:"capacity"`
		MaximumLeaseSeconds int    `json:"maximumLeaseSeconds"`
		LastSweepAgeMillis  int64  `json:"lastSweepAgeMillis"`
		SingleNode          bool   `json:"singleNode"`
	}{2, voltyNodeEpoch, status.Ready, stats.Used, stats.Capacity, 30, status.AgeMillis, true})
}
