package main

import (
	"bytes"
	"encoding/binary"
	"strings"
	"testing"
)

const requestUpgrade = "GET /rtc?access_token=SECRET HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"

func frame(op byte, final bool, payload []byte) []byte {
	first := op
	if final {
		first |= 128
	}
	b := []byte{first, 128}
	if len(payload) < 126 {
		b[1] |= byte(len(payload))
	} else {
		b[1] |= 126
		b = binary.BigEndian.AppendUint16(b, uint16(len(payload)))
	}
	key := []byte{7, 3, 1, 9}
	b = append(b, key...)
	for i, v := range payload {
		b = append(b, v^key[i%4])
	}
	return b
}
func newTestStream() (*stream, *[][]byte) {
	got := new([][]byte)
	s := newStream(true, func(b []byte) { *got = append(*got, bytes.Clone(b)) })
	return s, got
}
func TestSegmentedUpgradeAndFragmentedMessage(t *testing.T) {
	s, got := newTestStream()
	s.start(101)
	header := []byte(requestUpgrade + "\r\n")
	b := append(header, frame(2, false, []byte("an"))...)
	b = append(b, frame(9, true, []byte("ping"))...)
	b = append(b, frame(0, true, []byte("swer"))...)
	for i := range b {
		s.add(uint32(101+i), b[i:i+1])
	}
	if s.bad || len(*got) != 1 || string((*got)[0]) != "answer" {
		t.Fatalf("complete message not emitted: bad=%v messages=%d", s.bad, len(*got))
	}
}
func TestOutOfOrderAndRetransmissionEmitExactlyOnce(t *testing.T) {
	s, got := newTestStream()
	s.start(10)
	b := append([]byte(requestUpgrade+"\r\n"), frame(2, true, []byte("answer"))...)
	s.add(20, b[10:])
	if len(*got) != 0 {
		t.Fatal("gap emitted a message")
	}
	s.add(10, b[:15])
	s.add(10, b)
	if s.bad || len(*got) != 1 {
		t.Fatalf("duplicate or missing event: bad=%v messages=%d", s.bad, len(*got))
	}
}
func TestPartialAnswerIsNotDelivery(t *testing.T) {
	s, got := newTestStream()
	s.start(1)
	b := append([]byte(requestUpgrade+"\r\n"), frame(2, true, []byte("answer"))...)
	s.add(1, b[:len(b)-1])
	if len(*got) != 0 {
		t.Fatal("partial frame counted as delivered")
	}
	if !s.incomplete() {
		t.Fatal("partial frame not reported")
	}
}
func TestCompressionFailsClosed(t *testing.T) {
	s, got := newTestStream()
	s.start(1)
	s.add(1, []byte(requestUpgrade+"Sec-WebSocket-Extensions: permessage-deflate\r\n\r\n"))
	if !s.bad || len(*got) != 0 {
		t.Fatal("unsupported compression accepted")
	}
}
func TestConflictingOverlapFailsClosed(t *testing.T) {
	s, _ := newTestStream()
	s.start(1)
	s.add(5, []byte("abcd"))
	s.add(5, []byte("axcd"))
	if !s.bad {
		t.Fatal("conflicting retransmission accepted")
	}
}
func TestBoundedBufferFailsClosed(t *testing.T) {
	s, _ := newTestStream()
	s.start(1)
	s.add(2, []byte(strings.Repeat("x", maxBuffered+1)))
	if !s.bad {
		t.Fatal("unbounded gap buffer")
	}
}
func TestUnmaskedClientFrameFailsClosed(t *testing.T) {
	s, got := newTestStream()
	s.start(1)
	s.add(1, append([]byte(requestUpgrade+"\r\n"), []byte{130, 1, 'x'}...))
	if !s.bad || len(*got) != 0 {
		t.Fatal("unmasked client accepted")
	}
}

func TestOrdinaryHTTPRequestBodyIsNotWebSocketDelivery(t *testing.T) {
	s, got := newTestStream()
	s.start(1)
	s.add(1, append([]byte("GET /rtc HTTP/1.1\r\nContent-Length: 7\r\n\r\n"), frame(2, true, []byte("x"))...))
	if !s.bad || len(*got) != 0 {
		t.Fatal("HTTP body emitted as WebSocket message")
	}
}

func TestNonCanonicalExtendedLengthFailsClosed(t *testing.T) {
	s, got := newTestStream()
	s.start(1)
	b := []byte("GET /rtc HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n")
	b = append(b, []byte{130, 254, 0, 1, 7, 3, 1, 9, 'x' ^ 7}...)
	s.add(1, b)
	if !s.bad || len(*got) != 0 {
		t.Fatal("noncanonical length emitted")
	}
}

func TestServerUpgradeRequiresExactStatusAndHeaders(t *testing.T) {
	valid := "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
	for _, header := range []string{valid, strings.Replace(valid, " 101 ", " 1010 ", 1), strings.Replace(valid, "Upgrade: websocket\r\n", "", 1)} {
		messages := 0
		s := newStream(false, func([]byte) { messages++ })
		s.expectedAccept = acceptForKey("dGhlIHNhbXBsZSBub25jZQ==")
		s.start(1)
		s.add(1, append([]byte(header), []byte{130, 1, 'x'}...))
		if header == valid {
			if s.bad || messages != 1 {
				t.Fatal("valid server handshake rejected")
			}
		} else if !s.bad || messages != 0 {
			t.Fatal("invalid server handshake emitted")
		}
	}
}

func TestExtendedLengthBoundaries(t *testing.T) {
	for _, n := range []int{126, 65535, 65536} {
		s, got := newTestStream()
		s.start(1)
		b := []byte{130, 254}
		if n == 65536 {
			b[1] = 255
			b = binary.BigEndian.AppendUint64(b, uint64(n))
		} else {
			b = binary.BigEndian.AppendUint16(b, uint16(n))
		}
		b = append(b, 7, 3, 1, 9)
		payload := bytes.Repeat([]byte{'x'}, n)
		for i, v := range payload {
			b = append(b, v^[]byte{7, 3, 1, 9}[i%4])
		}
		s.add(1, append([]byte(requestUpgrade+"\r\n"), b...))
		if s.bad || len(*got) != 1 || !bytes.Equal((*got)[0], payload) {
			t.Fatalf("valid length %d rejected", n)
		}
	}
	for _, n := range []uint64{0, 1, 65535} {
		s, got := newTestStream()
		s.start(1)
		b := binary.BigEndian.AppendUint64([]byte{130, 255}, n)
		s.add(1, append([]byte(requestUpgrade+"\r\n"), b...))
		if !s.bad || len(*got) != 0 {
			t.Fatal("noncanonical 64-bit length accepted")
		}
	}
}

func TestMalformedCloseCannotBeFollowedByDelivery(t *testing.T) {
	s, got := newTestStream()
	s.start(1)
	b := append([]byte(requestUpgrade+"\r\n"), frame(8, true, []byte{1})...)
	b = append(b, frame(2, true, []byte("answer"))...)
	s.add(1, b)
	if !s.bad || len(*got) != 0 {
		t.Fatal("data emitted after malformed close")
	}
}

func TestValidCloseEndsDelivery(t *testing.T) {
	s, got := newTestStream()
	s.start(1)
	b := append([]byte(requestUpgrade+"\r\n"), frame(8, true, []byte{3, 232})...)
	b = append(b, frame(2, true, []byte("answer"))...)
	s.add(1, b)
	if s.bad || len(*got) != 0 {
		t.Fatal("data emitted after close")
	}
}

func TestUncorrelatedServerAcceptCannotEmit(t *testing.T) {
	messages := 0
	s := newStream(false, func([]byte) { messages++ })
	s.start(1)
	s.expectedAccept = acceptForKey("dGhlIHNhbXBsZSBub25jZQ==")
	header := "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: AAAAAAAAAAAAAAAAAAAAAAAAAAA=\r\n\r\n"
	s.add(1, append([]byte(header), []byte{130, 1, 'x'}...))
	if !s.bad || messages != 0 {
		t.Fatal("uncorrelated server accept emitted")
	}
}

func TestClientHandshakeCorrelatesServerAccept(t *testing.T) {
	messages := 0
	server := newStream(false, func([]byte) { messages++ })
	client, _ := newTestStream()
	client.onAccept = func(accept string) { server.expectedAccept = accept }
	client.start(1)
	server.start(1)
	client.add(1, []byte(requestUpgrade+"\r\n"))
	server.add(1, append([]byte("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"), []byte{130, 1, 'x'}...))
	if client.bad || server.bad || messages != 1 {
		t.Fatal("correlated handshake not accepted")
	}
}
