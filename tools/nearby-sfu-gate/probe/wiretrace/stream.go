package main

import (
	"bytes"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"strings"
	"unicode/utf8"
)

const maxBuffered = 1 << 20

// Starts only at a captured SYN. Mid-connection capture cannot establish delivery.
// Buffers exist only in memory. Never include them or HTTP headers in diagnostics.
type stream struct {
	next                               uint32
	started, bad, upgraded, fragmented bool
	masked                             bool
	pending                            map[uint32]byte
	buf, message                       []byte
	emit                               func([]byte)
	closed                             bool
	expectedAccept                     string
	onAccept                           func(string)
}

func newStream(masked bool, emit func([]byte)) *stream {
	return &stream{masked: masked, pending: map[uint32]byte{}, emit: emit}
}
func (s *stream) start(seq uint32) { s.started = true; s.next = seq }
func (s *stream) incomplete() bool {
	return !s.started || s.bad || len(s.pending) > 0 || len(s.buf) > 0 || s.fragmented
}
func (s *stream) fail() { s.bad = true; s.pending = nil; s.buf = nil; s.message = nil }
func (s *stream) add(seq uint32, payload []byte) {
	if s.bad || s.closed || !s.started {
		return
	}
	if len(payload) > maxBuffered {
		s.fail()
		return
	}
	for i, v := range payload {
		at := seq + uint32(i)
		distance := int32(at - s.next)
		if distance < 0 {
			continue
		}
		if distance > maxBuffered {
			s.fail()
			return
		}
		if old, ok := s.pending[at]; ok && old != v {
			s.fail()
			return
		}
		s.pending[at] = v
	}
	if len(s.pending)+len(s.buf)+len(s.message) > maxBuffered {
		s.fail()
		return
	}
	for {
		v, ok := s.pending[s.next]
		if !ok {
			break
		}
		delete(s.pending, s.next)
		s.next++
		s.buf = append(s.buf, v)
	}
	if len(s.pending) == 0 {
		s.pending = map[uint32]byte{}
	}
	s.parse()
}
func (s *stream) parse() {
	if !s.upgraded {
		end := bytes.Index(s.buf, []byte("\r\n\r\n"))
		if end < 0 {
			if len(s.buf) > 32768 {
				s.fail()
			}
			return
		}
		header := s.buf[:end]
		if !validUpgrade(header, s.masked) {
			s.fail()
			return
		}
		if s.masked {
			if s.onAccept != nil {
				s.onAccept(acceptForKey(headerValue(header, "sec-websocket-key")))
			}
		} else if s.expectedAccept == "" || headerValue(header, "sec-websocket-accept") != s.expectedAccept {
			s.fail()
			return
		}
		s.buf = s.buf[end+4:]
		s.upgraded = true
	}
	for !s.bad && len(s.buf) >= 2 {
		b := s.buf
		final := b[0]&128 != 0
		opcode := b[0] & 15
		masked := b[1]&128 != 0
		if b[0]&112 != 0 || masked != s.masked {
			s.fail()
			return
		}
		n := uint64(b[1] & 127)
		offset := 2
		if n == 126 {
			if len(b) < 4 {
				return
			}
			n = uint64(binary.BigEndian.Uint16(b[2:4]))
			if n < 126 {
				s.fail()
				return
			}
			offset = 4
		}
		if n == 127 {
			if len(b) < 10 {
				return
			}
			n = binary.BigEndian.Uint64(b[2:10])
			if n < 65536 {
				s.fail()
				return
			}
			offset = 10
		}
		if n > maxBuffered || (opcode >= 8 && (!final || n > 125)) {
			s.fail()
			return
		}
		var key []byte
		if masked {
			if len(b) < offset+4 {
				return
			}
			key = b[offset : offset+4]
			offset += 4
		}
		if uint64(len(b)-offset) < n {
			return
		}
		payload := bytes.Clone(b[offset : offset+int(n)])
		if masked {
			for i := range payload {
				payload[i] ^= key[i%4]
			}
		}
		s.buf = b[offset+int(n):]
		switch opcode {
		case 8:
			if !validClose(payload) {
				s.fail()
				return
			}
			s.closed = true
			s.buf = nil
			s.pending = nil
			s.message = nil
			s.fragmented = false
			return
		case 9, 10: // Ping/pong do not interrupt a fragmented message.
		case 2:
			if s.fragmented {
				s.fail()
				return
			}
			s.message = payload
			s.fragmented = !final
			if final {
				s.emit(s.message)
				s.message = nil
			}
		case 0:
			if !s.fragmented || len(s.message)+len(payload) > maxBuffered {
				s.fail()
				return
			}
			s.message = append(s.message, payload...)
			if final {
				s.emit(s.message)
				s.message = nil
				s.fragmented = false
			}
		default:
			s.fail()
			return // LiveKit's observed signal transport uses binary protobuf.
		}
	}
}

func headerValue(raw []byte, name string) string {
	for _, line := range strings.Split(string(raw), "\r\n")[1:] {
		key, value, _ := strings.Cut(line, ":")
		if strings.EqualFold(strings.TrimSpace(key), name) {
			return strings.TrimSpace(value)
		}
	}
	return ""
}

func acceptForKey(key string) string {
	// SHA-1 is mandated by the WebSocket handshake, not used for authentication.
	sum := sha1.Sum([]byte(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
	return base64.StdEncoding.EncodeToString(sum[:])
}

func validClose(payload []byte) bool {
	if len(payload) == 0 {
		return true
	}
	if len(payload) == 1 {
		return false
	}
	code := binary.BigEndian.Uint16(payload[:2])
	valid := code == 1000 || code == 1001 || code == 1002 || code == 1003 || (code >= 1007 && code <= 1014) || (code >= 3000 && code <= 4999)
	return valid && utf8.Valid(payload[2:])
}

func validUpgrade(raw []byte, client bool) bool {
	lines := strings.Split(string(raw), "\r\n")
	first := strings.Fields(lines[0])
	if client {
		if len(first) != 3 || first[0] != "GET" || strings.SplitN(first[1], "?", 2)[0] != "/rtc" || first[2] != "HTTP/1.1" {
			return false
		}
	} else if len(first) < 2 || first[0] != "HTTP/1.1" || first[1] != "101" {
		return false
	}
	headers := map[string]string{}
	for _, line := range lines[1:] {
		name, value, ok := strings.Cut(line, ":")
		if !ok {
			return false
		}
		name = strings.ToLower(strings.TrimSpace(name))
		if _, duplicate := headers[name]; duplicate {
			return false
		}
		headers[name] = strings.TrimSpace(value)
	}
	if strings.ToLower(headers["upgrade"]) != "websocket" {
		return false
	}
	connectionUpgrade := false
	for _, token := range strings.Split(headers["connection"], ",") {
		if strings.EqualFold(strings.TrimSpace(token), "upgrade") {
			connectionUpgrade = true
		}
	}
	if !connectionUpgrade {
		return false
	}
	if _, present := headers["sec-websocket-extensions"]; present {
		return false
	}
	if headers["transfer-encoding"] != "" || (headers["content-length"] != "" && headers["content-length"] != "0") {
		return false
	}
	if client {
		key, err := base64.StdEncoding.DecodeString(headers["sec-websocket-key"])
		return err == nil && len(key) == 16 && headers["sec-websocket-version"] == "13"
	}
	accept, err := base64.StdEncoding.DecodeString(headers["sec-websocket-accept"])
	return err == nil && len(accept) == 20
}
