package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"github.com/go-logr/logr"
	"github.com/livekit/protocol/livekit"
	protocolLogger "github.com/livekit/protocol/logger"
	lksdk "github.com/livekit/server-sdk-go/v2"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
	"os"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type receiver struct {
	room    *lksdk.Room
	packets sync.Map
}

func join(token string) (*receiver, error) {
	r := &receiver{}
	cb := lksdk.NewRoomCallback()
	cb.OnTrackSubscribed = func(track *webrtc.TrackRemote, pub *lksdk.RemoteTrackPublication, participant *lksdk.RemoteParticipant) {
		sender := participant.Identity()
		value, _ := r.packets.LoadOrStore(sender, &atomic.Int64{})
		counter := value.(*atomic.Int64)
		go func() {
			for {
				packet, _, err := track.ReadRTP()
				if err != nil {
					return
				}
				if len(packet.Payload) > 0 {
					counter.Add(1)
				}
			}
		}()
	}
	var err error
	r.room, err = lksdk.ConnectToRoomWithToken("http://127.0.0.1:17881", token, cb)
	return r, err
}
func (r *receiver) count(identity string) int64 {
	if v, ok := r.packets.Load(identity); ok {
		return v.(*atomic.Int64).Load()
	}
	return 0
}
func publish(r *receiver) (func(), error) {
	track, err := lksdk.NewLocalSampleTrack(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeOpus, ClockRate: 48000, Channels: 2})
	if err != nil {
		return nil, err
	}
	if _, err = r.room.LocalParticipant.PublishTrack(track, &lksdk.TrackPublicationOptions{Name: "synthetic-opus", Source: livekit.TrackSource_MICROPHONE}); err != nil {
		track.Close()
		return nil, err
	}
	done := make(chan struct{})
	var once sync.Once
	go func() {
		ticker := time.NewTicker(20 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				return
			case <-ticker.C:
				_ = track.WriteSample(media.Sample{Data: []byte{0xf8, 0xff, 0xfe}, Duration: 20 * time.Millisecond}, nil)
			}
		}
	}()
	return func() { once.Do(func() { close(done); track.Close() }) }, nil
}

func main() {
	lksdk.SetLogger(protocolLogger.LogRLogger(logr.Discard()))
	receivers := map[string]*receiver{}
	stops := []func(){}
	defer func() {
		for _, stop := range stops {
			stop()
		}
		for _, r := range receivers {
			r.room.Disconnect()
		}
	}()
	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 4096), 65536)
	for scanner.Scan() {
		var req struct {
			Command  string `json:"command"`
			Identity string `json:"identity"`
			Token    string `json:"token"`
		}
		if json.Unmarshal(scanner.Bytes(), &req) != nil {
			fmt.Println(`{"error":"invalid_command"}`)
			return
		}
		switch req.Command {
		case "join":
			if receivers[req.Identity] != nil {
				fmt.Println(`{"error":"duplicate_identity"}`)
				return
			}
			r, err := join(req.Token)
			if err != nil {
				message := strings.ReplaceAll(err.Error(), req.Token, "<redacted>")
				message = regexp.MustCompile(`(?:https?|wss?)://[^ \t\n]+`).ReplaceAllString(message, "<url>")
				message = regexp.MustCompile(`[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}`).ReplaceAllString(message, "<jwt>")
				data, _ := json.Marshal(map[string]string{"error": "sdk_connect_failed: " + message})
				fmt.Println(string(data))
				return
			}
			receivers[req.Identity] = r
			stop, err := publish(r)
			if err != nil {
				fmt.Println(`{"error":"publish_failed"}`)
				return
			}
			stops = append(stops, stop)
			fmt.Println(`{"joined":true}`)
		case "counts":
			counts := map[string]map[string]int64{}
			for id, r := range receivers {
				counts[id] = map[string]int64{}
				for sender := range receivers {
					if sender != id {
						counts[id][sender] = r.count(sender)
					}
				}
			}
			data, _ := json.Marshal(counts)
			fmt.Println(string(data))
		case "close":
			fmt.Println(`{"closed":true}`)
			return
		default:
			fmt.Println(`{"error":"unknown_command"}`)
			return
		}
	}
}
