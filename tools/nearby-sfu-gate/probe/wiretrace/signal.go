package main

import (
	"github.com/livekit/protocol/livekit"
	"google.golang.org/protobuf/proto"
	"strings"
)

// Only these fixed semantic fields can leave the observer. Raw signaling stays
// in bounded transient memory; even decode errors must not export their input.
type signalEvent struct {
	Kind          string `json:"kind"`
	AudioSections int    `json:"audioSections"`
}

func signalSummary(client bool, data []byte) (signalEvent, bool) {
	var sdp, kind string
	if client {
		var r livekit.SignalRequest
		if proto.Unmarshal(data, &r) != nil {
			return signalEvent{}, false
		}
		if a := r.GetAnswer(); a != nil {
			sdp = a.Sdp
			kind = "answer"
		} else {
			return signalEvent{}, false
		}
	} else {
		var r livekit.SignalResponse
		if proto.Unmarshal(data, &r) != nil {
			return signalEvent{}, false
		}
		if o := r.GetOffer(); o != nil {
			sdp = o.Sdp
			kind = "offer"
		} else {
			return signalEvent{}, false
		}
	}
	n := 0
	for _, line := range strings.Split(sdp, "\n") {
		if strings.HasPrefix(line, "m=audio ") {
			n++
		}
	}
	return signalEvent{Kind: kind, AudioSections: n}, true
}
