package main

import (
	"encoding/json"
	"github.com/livekit/protocol/livekit"
	"google.golang.org/protobuf/proto"
	"strings"
	"testing"
)

func TestSignalWhitelistDoesNotExportSDP(t *testing.T) {
	sdp := "v=0\r\na=ice-pwd:SECRET\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=recvonly\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
	b, err := proto.Marshal(&livekit.SignalRequest{Message: &livekit.SignalRequest_Answer{Answer: &livekit.SessionDescription{Sdp: sdp}}})
	if err != nil {
		t.Fatal(err)
	}
	e, ok := signalSummary(true, b)
	if !ok || e.Kind != "answer" || e.AudioSections != 1 {
		t.Fatal("answer not decoded")
	}
	encoded, _ := json.Marshal(e)
	for _, secret := range []string{"SECRET", "ice-pwd", "webrtc-datachannel", "Sdp"} {
		if strings.Contains(string(encoded), secret) {
			t.Fatal("unapproved field exported")
		}
	}
}
func TestMalformedSignalIsNotDelivery(t *testing.T) {
	_, ok := signalSummary(true, []byte{255})
	if ok {
		t.Fatal("malformed protobuf counted")
	}
}
