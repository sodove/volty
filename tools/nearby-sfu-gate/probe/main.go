package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	"github.com/livekit/protocol/auth"
	"github.com/livekit/protocol/livekit"
	lksdk "github.com/livekit/server-sdk-go/v2"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
	"google.golang.org/protobuf/proto"
)

const host = "http://127.0.0.1:17881"
const proxied = "http://127.0.0.1:17884"
const key = "nearby-probe"
const secret = "local-nearby-probe-secret-not-production-20261003"

var refreshed sync.Map // opaque credentials, never printed or persisted

func claims(token string) map[string]any {
	parts := strings.Split(token, ".")
	result := map[string]any{}
	if len(parts) == 3 {
		raw, _ := base64.RawURLEncoding.DecodeString(parts[1])
		_ = json.Unmarshal(raw, &result)
	}
	return result
}
func proxy() (*http.Server, error) {
	upstream, _ := url.Parse(host)
	ordinary := httputil.NewSingleHostReverseProxy(upstream)
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !websocket.IsWebSocketUpgrade(r) {
			ordinary.ServeHTTP(w, r)
			return
		}
		target := "ws://127.0.0.1:17881" + r.URL.RequestURI()
		headers := http.Header{}
		headers.Set("Authorization", r.Header.Get("Authorization"))
		remote, _, err := websocket.DefaultDialer.Dial(target, headers)
		if err != nil {
			http.Error(w, "upstream handshake failed", 502)
			return
		}
		upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
		local, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			_ = remote.Close()
			return
		}
		credential := r.URL.Query().Get("access_token")
		if credential == "" {
			credential = strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		}
		identity, _ := claims(credential)["sub"].(string)
		done := make(chan struct{}, 2)
		go func() {
			defer func() { done <- struct{}{} }()
			for {
				typ, data, e := local.ReadMessage()
				if e != nil {
					return
				}
				if remote.WriteMessage(typ, data) != nil {
					return
				}
			}
		}()
		go func() {
			defer func() { done <- struct{}{} }()
			for {
				typ, data, e := remote.ReadMessage()
				if e != nil {
					return
				}
				if typ == websocket.BinaryMessage {
					var msg livekit.SignalResponse
					if proto.Unmarshal(data, &msg) == nil && msg.GetRefreshToken() != "" {
						refreshed.Store(identity, msg.GetRefreshToken())
					}
				}
				if local.WriteMessage(typ, data) != nil {
					return
				}
			}
		}()
		<-done
		_ = local.Close()
		_ = remote.Close()
	})
	listener, err := net.Listen("tcp", "127.0.0.1:17884")
	if err != nil {
		return nil, err
	}
	server := &http.Server{Handler: handler}
	go server.Serve(listener)
	return server, nil
}

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
	r.room, err = lksdk.ConnectToRoomWithToken(proxied, token, cb)
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
func token(room, identity string) string {
	t, err := auth.NewAccessToken(key, secret).SetIdentity(identity).SetValidFor(60 * time.Second).SetVideoGrant(&auth.VideoGrant{RoomJoin: true, Room: room}).ToJWT()
	if err != nil {
		panic(err)
	}
	return t
}
func waitMedia(r *receiver, from string, previous int64) bool {
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		if r.count(from)-previous >= 10 {
			return true
		}
		time.Sleep(50 * time.Millisecond)
	}
	return false
}

func adminBearer(room string) string {
	value, err := auth.NewAccessToken(key, secret).SetVideoGrant(&auth.VideoGrant{RoomAdmin: true, Room: room}).SetValidFor(time.Minute).ToJWT()
	if err != nil {
		panic("admin token creation failed")
	}
	return value
}
func control(path, room, identity, bearer string, ttl int) int {
	body, _ := json.Marshal(map[string]any{"room": room, "identity": identity, "ttlSeconds": ttl})
	if strings.Contains(path, "/barrier/") {
		body, _ = json.Marshal(map[string]any{"room": room, "identity": identity})
	}
	req, _ := http.NewRequest(http.MethodPost, host+path, strings.NewReader(string(body)))
	req.Header.Set("Authorization", "Bearer "+bearer)
	req.Header.Set("Content-Type", "application/json")
	response, err := (&http.Client{Timeout: 7 * time.Second}).Do(req)
	if err != nil {
		return 0
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, response.Body)
	return response.StatusCode
}
func require(ok bool, message string) {
	if !ok {
		panic(message)
	}
}
func mustLease(action, room, identity string, ttl int) {
	require(control("/volty/leases/"+action, room, identity, adminBearer(room), ttl) == 204, "lease control failed: "+action)
}
func denied(room, identity, value, name string) {
	candidate, err := join(value)
	if err == nil {
		candidate.room.Disconnect()
		panic("unexpected admission: " + name)
	}
	fmt.Printf("case=%s admitted=false\n", name)
}
func awaitBarrier(room, identity, action string) {
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if control("/volty/test/barrier/"+action, room, identity, adminBearer(room), 0) == 204 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	panic("barrier precondition missing: " + action)
}
func main() {
	proxyServer, err := proxy()
	require(err == nil, "proxy startup failed")
	defer proxyServer.Close()
	nonce := uuid.NewString()
	room := "volty-admission-probe-" + nonce
	admin := lksdk.NewRoomServiceClient(host, key, secret)
	defer admin.DeleteRoom(context.Background(), &livekit.DeleteRoomRequest{Room: room})
	fmt.Printf("nonce=%s\n", nonce)
	// Valid participant credentials must not mutate the registry. Wrong room-admin scope too.
	require(control("/volty/leases/register", room, "untrusted", token(room, "untrusted"), 30) == 403, "participant mutated lease")
	require(control("/volty/leases/register", room, "untrusted", adminBearer(room+"-wrong"), 30) == 403, "wrong-room admin mutated lease")
	denied(room, "untrusted", token(room, "untrusted"), "unknown_identity")
	fmt.Println("participant_control_denied=true wrong_room_control_denied=true")
	for _, id := range []string{"A", "B", "C"} {
		mustLease("register", room, id, 30)
	}
	renewalContext, cancelRenewals := context.WithCancel(context.Background())
	defer cancelRenewals()
	renewalErrors := make(chan bool, 1)
	go func() {
		ticker := time.NewTicker(5 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-renewalContext.Done():
				return
			case <-ticker.C:
				for _, id := range []string{"A", "C"} {
					if control("/volty/leases/renew", room, id, adminBearer(room), 30) != 204 {
						select {
						case renewalErrors <- true:
						default:
						}
						return
					}
				}
			}
		}
	}()
	a, err := join(token(room, "A"))
	require(err == nil, "A join failed")
	defer a.room.Disconnect()
	c, err := join(token(room, "C"))
	require(err == nil, "C join failed")
	defer c.room.Disconnect()
	original := token(room, "B")
	b, err := join(original)
	require(err == nil, "B join failed")
	defer b.room.Disconnect()
	stopA, err := publish(a)
	require(err == nil, "A publish failed")
	defer stopA()
	stopB, err := publish(b)
	require(err == nil, "B publish failed")
	defer stopB()
	stopC, err := publish(c)
	require(err == nil, "C publish failed")
	defer stopC()
	require(waitMedia(b, "A", 0) && waitMedia(c, "B", 0) && waitMedia(a, "C", 0), "INVALID duplex baseline")
	fmt.Printf("baseline_B_receive=%d C_from_B=%d A_from_C=%d\n", b.count("A"), c.count("B"), a.count("C"))
	captured, ok := refreshed.Load("B")
	require(ok, "SFU refresh not captured")
	refresh := captured.(string)
	fmt.Printf("original_exp=%v refreshed_exp=%v refresh_captured=true\n", claims(original)["exp"], claims(refresh)["exp"])
	revokeStart := time.Now()
	mustLease("revoke", room, "B", 0)
	fmt.Printf("revoke_ack_ms=%d\n", time.Since(revokeStart).Milliseconds())
	// Keep both revoked send/receive clients active; don't substitute client disconnect for server close.
	time.Sleep(300 * time.Millisecond)
	beforeReceive, beforeForward := b.count("A"), c.count("B")
	beforeA, beforeC := a.count("C"), c.count("A")
	time.Sleep(time.Second)
	require(b.count("A") == beforeReceive && c.count("B") == beforeForward, "media continued after revoke")
	require(a.count("C") > beforeA && c.count("A") > beforeC, "revoke killed surviving peers")
	fmt.Printf("post_ack_receive_delta=%d forward_delta=%d survivors_duplex=true\n", b.count("A")-beforeReceive, c.count("B")-beforeForward)
	denied(room, "B", original, "revoked_original")
	denied(room, "B", refresh, "revoked_refreshed")
	require(control("/volty/leases/register", room, "B", adminBearer(room), 30) == 409, "register resurrected revoked session")
	require(control("/volty/leases/renew", room, "B", adminBearer(room), 30) == 409, "renew resurrected revoked session")
	// A completed admit raced with a revoke cannot leave a participant after revoke ACK.
	mustLease("register", room, "R", 30)
	require(control("/volty/test/barrier/arm", room, "R", adminBearer(room), 0) == 204, "barrier arm failed")
	raceToken := token(room, "R")
	raceJoin := make(chan *receiver, 1)
	go func() {
		r, e := join(raceToken)
		if e != nil {
			raceJoin <- nil
		} else {
			raceJoin <- r
		}
	}()
	awaitBarrier(room, "R", "entered")
	raceRevoke := make(chan int, 1)
	go func() { raceRevoke <- control("/volty/leases/revoke", room, "R", adminBearer(room), 0) }()
	awaitBarrier(room, "R", "revoking")
	select {
	case <-raceRevoke:
		panic("revoke acknowledged before pending admission finished")
	default:
	}
	require(control("/volty/test/barrier/release", room, "R", adminBearer(room), 0) == 204, "barrier release failed")
	require(<-raceRevoke == 204, "race revoke failed")
	if r := <-raceJoin; r != nil {
		defer r.room.Disconnect()
	}
	roster, err := admin.ListParticipants(context.Background(), &livekit.ListParticipantsRequest{Room: room})
	require(err == nil, "roster request failed")
	for _, p := range roster.Participants {
		require(p.Identity != "R", "raced participant remained after ACK")
	}
	denied(room, "R", raceToken, "raced_original")
	fmt.Println("race_admission_entered=true revoke_entered=true post_ack_participant_absent=true")
	// Expiry closes media without any explicit leave request; original JWT is still valid.
	mustLease("register", room, "D", 8)
	expiryStart := time.Now()
	dToken := token(room, "D")
	d, err := join(dToken)
	require(err == nil, "D join failed")
	defer d.room.Disconnect()
	stopD, err := publish(d)
	require(err == nil, "D publish failed")
	defer stopD()
	require(waitMedia(c, "D", 0) && waitMedia(d, "A", 0), "INVALID expiry baseline")
	wait := time.Until(expiryStart.Add(10 * time.Second))
	if wait > 0 {
		time.Sleep(wait)
	}
	dReceive, dForward := d.count("A"), c.count("D")
	time.Sleep(time.Second)
	require(d.count("A") == dReceive && c.count("D") == dForward, "expired media continued")
	require(control("/volty/leases/renew", room, "D", adminBearer(room), 30) == 409, "late renewal resurrected expiry")
	denied(room, "D", dToken, "expired_original_still_valid_JWT")
	fmt.Println("expiry_without_client_stop=true post_expiry_media_delta=0 late_renew_denied=true")
	// A refreshed token remains denied beyond original expiry; survivors renew independently.
	expiry := int64(claims(original)["exp"].(float64))
	wait = time.Until(time.Unix(expiry+1, 0))
	if wait > 0 {
		time.Sleep(wait)
	}
	require(time.Now().Unix() > expiry, "original has not expired")
	denied(room, "B", refresh, "revoked_refreshed_after_original_expiry")
	select {
	case <-renewalErrors:
		panic("survivor renewal failed")
	default:
	}
	fmt.Println("matrix_passed=true synthetic_RTP_only Android_audibility_not_tested=true restart_invariant_unit_test_only=true")
}
