package main

import (
	"encoding/binary"
	"encoding/json"
	"flag"
	"golang.org/x/sys/unix"
	"net"
	"os"
	"time"
)

type flow struct {
	id             int
	startSeq       uint32
	client, server *stream
}

func main() {
	seconds := flag.Int("seconds", 90, "Bounded passive observation duration (1..120)")
	flag.Parse()
	if *seconds < 1 || *seconds > 120 {
		os.Exit(2)
	}
	enc := json.NewEncoder(os.Stdout)
	fail := func(kind string) { _ = enc.Encode(map[string]any{"status": kind}); os.Exit(2) }
	iface, err := net.InterfaceByName("lo")
	if err != nil {
		fail("loopback_unavailable")
	}
	fd, err := unix.Socket(unix.AF_PACKET, unix.SOCK_RAW, int((unix.ETH_P_IP>>8)|(unix.ETH_P_IP<<8)&0xffff))
	if err != nil {
		fail("capture_socket_unavailable")
	}
	defer unix.Close(fd)
	if unix.Bind(fd, &unix.SockaddrLinklayer{Protocol: uint16((unix.ETH_P_IP >> 8) | (unix.ETH_P_IP<<8)&0xffff), Ifindex: iface.Index}) != nil {
		fail("capture_bind_failed")
	}
	if unix.SetsockoptTimeval(fd, unix.SOL_SOCKET, unix.SO_RCVTIMEO, &unix.Timeval{Sec: 1}) != nil {
		fail("capture_timeout_failed")
	}
	flows := map[uint16]*flow{}
	allFlows := []*flow{}
	ordinal := 0
	packets := 0
	unknown := 0
	frame := make([]byte, 65536)
	deadline := time.Now().Add(time.Duration(*seconds) * time.Second)
	_ = enc.Encode(map[string]any{"status": "capture_started", "port": 17880, "seconds": *seconds})
	for time.Now().Before(deadline) {
		n, from, e := unix.Recvfrom(fd, frame, 0)
		if e == unix.EAGAIN || e == unix.EINTR {
			continue
		}
		if e != nil {
			fail("capture_read_failed")
		}
		addr, ok := from.(*unix.SockaddrLinklayer)
		if !ok || addr.Pkttype == unix.PACKET_OUTGOING {
			continue
		}
		if n < 54 || binary.BigEndian.Uint16(frame[12:14]) != 0x0800 {
			continue
		}
		ip := frame[14:n]
		ihl := int(ip[0]&15) * 4
		if ip[0]>>4 != 4 || ihl < 20 || len(ip) < ihl+20 || ip[9] != 6 {
			continue
		}
		if string(ip[12:16]) != "\x7f\x00\x00\x01" || string(ip[16:20]) != "\x7f\x00\x00\x01" {
			continue
		}
		tcp := ip[ihl:]
		src, dst := binary.BigEndian.Uint16(tcp[:2]), binary.BigEndian.Uint16(tcp[2:4])
		client := dst == 17880
		if !client && src != 17880 {
			continue
		}
		packets++
		total := int(binary.BigEndian.Uint16(ip[2:4]))
		off := int(tcp[12]>>4) * 4
		if total > len(ip) || total < ihl+off || off < 20 || binary.BigEndian.Uint16(ip[6:8])&0x3fff != 0 {
			unknown++
			continue
		}
		port := src
		if !client {
			port = dst
		}
		seq := binary.BigEndian.Uint32(tcp[4:8])
		flags := tcp[13]
		f := flows[port]
		if client && flags&2 != 0 && flags&16 == 0 && (f == nil || f.startSeq != seq) {
			if ordinal >= 64 {
				fail("connection_limit")
			}
			ordinal++
			f = &flow{id: ordinal, startSeq: seq}
			flows[port] = f
			allFlows = append(allFlows, f)
			emit := func(client bool) func([]byte) {
				return func(data []byte) {
					if summary, ok := signalSummary(client, data); ok {
						_ = enc.Encode(struct {
							Conn int   `json:"conn"`
							AtMs int64 `json:"atMs"`
							signalEvent
						}{f.id, time.Now().UnixMilli(), summary})
					}
				}
			}
			f.client = newStream(true, emit(true))
			f.server = newStream(false, emit(false))
			f.client.onAccept = func(accept string) { f.server.expectedAccept = accept }
			f.client.start(seq + 1)
		}
		if f == nil {
			unknown++
			continue
		}
		s := f.server
		if client {
			s = f.client
		}
		if flags&2 != 0 {
			if !s.started {
				s.start(seq + 1)
			}
			seq++
		}
		s.add(seq, tcp[off:total-ihl])
	}
	stats, err := unix.GetsockoptTpacketStats(fd, unix.SOL_PACKET, unix.PACKET_STATISTICS)
	if err != nil {
		fail("capture_stats_unavailable")
	}
	incomplete := 0
	invalid := 0
	for _, f := range allFlows {
		for _, s := range []*stream{f.client, f.server} {
			if s.incomplete() {
				incomplete++
			}
			if s.bad {
				invalid++
			}
		}
	}
	_ = enc.Encode(map[string]any{"status": "capture_finished", "packets": packets, "kernelDrops": stats.Drops, "unknownPackets": unknown, "connections": ordinal, "incompleteStreams": incomplete, "invalidStreams": invalid})
	// Positive complete messages prove observation on this loopback connection.
	// Zero counters alone do not prove absence or processing inside the SFU:
	// correlate the connection and inspect capture/decoder coverage independently.
}
