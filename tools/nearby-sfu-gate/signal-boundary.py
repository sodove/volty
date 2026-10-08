"""Semantic-only parser for an owned, non-persistent SFU DEBUG stream."""
import json
import re

MAX_LINE = 1024 * 1024
BOUNDARIES = {
    ("service/rtcservice.go", "received answer"): "service_read",
    ("rtc/participant.go", "received answer"): "participant_dispatch",
    ("rtc/transport.go", "processing answer"): "transport_process",
    ("rtc/transport.go", "negotiation failed"): "negotiation_failure",
}


class BoundaryTrace:
    def __init__(self, max_peers=64, max_events=2048):
        if not 1 <= max_peers <= 64 or not 1 <= max_events <= 2048:
            raise ValueError("Invalid diagnostic bounds")
        self.max_peers, self.max_events = max_peers, max_events
        self.peers = {}
        self.events = []
        self.health = {"malformed": 0, "oversized": 0, "peer_limit": 0, "event_limit": 0}

    def consume(self, line):
        if len(line) > MAX_LINE or len(line.encode("utf-8")) > MAX_LINE:
            self.health["oversized"] += 1
            return None
        parts = line.rstrip("\r\n").split("\t")
        if len(parts) != 6:
            return None
        caller = re.fullmatch(r"(service/rtcservice\.go|rtc/participant\.go|rtc/transport\.go):[0-9]+", parts[3])
        stage = BOUNDARIES.get((caller.group(1), parts[4])) if caller else None
        if stage is None:
            return None
        if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})", parts[0]):
            self.health["malformed"] += 1
            return None
        try:
            fields = json.loads(parts[5])
        except (ValueError, RecursionError):
            self.health["malformed"] += 1
            return None
        if not isinstance(fields, dict):
            self.health["malformed"] += 1
            return None
        peer = fields.get("participantID")
        answer_id = fields.get("answerId")
        invalid_id = "answerId" in fields and (type(answer_id) is not int or not 0 <= answer_id <= 0xffffffff)
        if not isinstance(peer, str) or not peer or len(peer) > 256 or invalid_id:
            self.health["malformed"] += 1
            return None
        if peer not in self.peers:
            if len(self.peers) >= self.max_peers:
                self.health["peer_limit"] += 1
                return None
            self.peers[peer] = len(self.peers) + 1
        if len(self.events) >= self.max_events:
            self.health["event_limit"] += 1
            return None
        if stage == "negotiation_failure" and fields.get("reason") == "negotiation timed out":
            stage = "negotiation_timeout"
        event = {"time": parts[0], "peer": self.peers[peer], "stage": stage}
        if stage in ("service_read", "participant_dispatch", "transport_process") and answer_id is not None:
            event["answer_id"] = answer_id
        self.events.append(event)
        return event
