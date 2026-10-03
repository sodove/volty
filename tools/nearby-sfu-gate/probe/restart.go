package main

import (
	"bufio"
	"context"
	"fmt"
	"github.com/google/uuid"
	"github.com/livekit/protocol/livekit"
	lksdk "github.com/livekit/server-sdk-go/v2"
	"os"
	"strings"
	"time"
)

func awaitReady(room string) readinessInfo {
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		status, info := readiness(room, adminBearer(room))
		if status == 200 && info.Ready && info.SingleNode && info.ProtocolVersion == 2 {
			return info
		}
		time.Sleep(50 * time.Millisecond)
	}
	panic("singleton readiness missing")
}

// Supervisor owns both SFU processes. JWTs stay in this process across the restart.
func runRestartMatrix() {
	proxyServer, err := proxy()
	require(err == nil, "restart proxy startup failed")
	defer proxyServer.Close()
	room := "volty-restart-probe-" + uuid.NewString()
	admin := lksdk.NewRoomServiceClient(host, key, secret)
	defer admin.DeleteRoom(context.Background(), &livekit.DeleteRoomRequest{Room: room})
	first := awaitReady(room)
	nodeEpoch = first.NodeEpoch
	oldNode := nodeEpoch
	for _, id := range []string{"old-A", "old-B"} {
		mustLease("register", room, id, 30)
	}
	oldOriginal := token(room, "old-B")
	a, err := join(token(room, "old-A"))
	require(err == nil, "old A join failed")
	defer a.room.Disconnect()
	b, err := join(oldOriginal)
	require(err == nil, "old B join failed")
	defer b.room.Disconnect()
	stopA, err := publish(a)
	require(err == nil, "old A publish failed")
	defer stopA()
	stopB, err := publish(b)
	require(err == nil, "old B publish failed")
	defer stopB()
	require(waitMedia(a, "old-B", 0) && waitMedia(b, "old-A", 0), "restart duplex baseline absent")
	value, captured := refreshed.Load("old-B")
	require(captured, "restart refresh missing")
	oldRefreshed := value.(string)
	fmt.Printf("restart_old_node_epoch=%s baseline_A=%d baseline_B=%d\n", oldNode, a.count("old-B"), b.count("old-A"))
	fmt.Println("restart_barrier_ready=true")
	command := make(chan string, 1)
	go func() { line, _ := bufio.NewReader(os.Stdin).ReadString('\n'); command <- strings.TrimSpace(line) }()
	select {
	case line := <-command:
		require(line == "server_restarted", "supervisor restart acknowledgement missing")
	case <-time.After(30 * time.Second):
		panic("supervisor restart timeout")
	}
	second := awaitReady(room)
	require(second.NodeEpoch != oldNode, "restart reused node incarnation")
	nodeEpoch = second.NodeEpoch
	require(time.Now().Unix() < int64(claims(oldOriginal)["exp"].(float64)), "INVALID original token already expired")
	denied(room, "old-B", oldOriginal, "restart_original_still_valid")
	denied(room, "old-B", oldRefreshed, "restart_refreshed")
	for _, action := range []string{"register", "renew", "revoke"} {
		require(controlAtNode("/volty/leases/"+action, room, "old-B", adminBearer(room), 30, oldNode) == 409, "restart accepted old-node control")
	}
	time.Sleep(300 * time.Millisecond)
	beforeA, beforeB := a.count("old-B"), b.count("old-A")
	time.Sleep(time.Second)
	require(a.count("old-B") == beforeA && b.count("old-A") == beforeB, "old media survived owned process restart")
	a.room.Disconnect()
	b.room.Disconnect()
	for _, id := range []string{"new-A", "new-B"} {
		mustLease("register", room, id, 30)
	}
	newA, err := join(token(room, "new-A"))
	require(err == nil, "fresh A join failed")
	defer newA.room.Disconnect()
	newB, err := join(token(room, "new-B"))
	require(err == nil, "fresh B join failed")
	defer newB.room.Disconnect()
	stopNewA, err := publish(newA)
	require(err == nil, "fresh A publish failed")
	defer stopNewA()
	stopNewB, err := publish(newB)
	require(err == nil, "fresh B publish failed")
	defer stopNewB()
	require(waitMedia(newA, "new-B", 0) && waitMedia(newB, "new-A", 0), "fresh explicit sessions not duplex")
	denied(room, "old-B", oldOriginal, "restart_original_after_fresh_sessions")
	fmt.Printf("restart_matrix_passed=true old_media_delta=0 original_and_refreshed_denied=true fresh_sessions_duplex=true new_node_epoch=%s\n", nodeEpoch)
}
