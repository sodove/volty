import importlib.util
import json
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("signal_boundary", pathlib.Path(__file__).with_name("signal-boundary.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
BoundaryTrace = module.BoundaryTrace


def record(caller, message, **fields):
    return "2026-10-08T10:20:30.123Z\tDEBUG\tlivekit\t" + caller + "\t" + message + "\t" + json.dumps(fields) + "\n"


class BoundaryTraceTest(unittest.TestCase):
    def test_three_boundaries_keep_one_private_ordinal_and_distinct_stages(self):
        trace = BoundaryTrace()
        for caller, message, stage in [
            ("service/rtcservice.go:620", "received answer", "service_read"),
            ("rtc/participant.go:1275", "received answer", "participant_dispatch"),
            ("rtc/transport.go:2936", "processing answer", "transport_process"),
        ]:
            event = trace.consume(record(caller, message, participantID="PRIVATE_PEER", answerId=0, transport="SUBSCRIBER"))
            self.assertIsNotNone(event)
            self.assertEqual(event["stage"], stage)
            self.assertEqual(event["peer"], 1)
        self.assertEqual(len(trace.events), 3)
        self.assertEqual(trace.health, {"malformed": 0, "oversized": 0, "peer_limit": 0, "event_limit": 0})

    def test_secret_fields_never_escape_and_processing_is_not_application(self):
        trace = BoundaryTrace()
        event = trace.consume(record("rtc/transport.go:2936", "processing answer", participantID="SECRET_ID", room="SECRET_ROOM", answerId=7, arbitrary="SECRET", answer={"sdp": "a=ice-pwd:SECRET"}, transport="SUBSCRIBER"))
        self.assertIsNotNone(event)
        self.assertEqual(set(event), {"time", "peer", "stage", "answer_id"})
        self.assertEqual(event["answer_id"], 7)
        encoded = json.dumps(trace.events)
        for secret in ("SECRET", "ice-pwd", "sdp", "applied", "SUBSCRIBER"):
            self.assertNotIn(secret, encoded)

    def test_duplicate_receipts_are_not_deduplicated(self):
        trace = BoundaryTrace()
        line = record("rtc/transport.go:2936", "processing answer", participantID="PEER", answerId=0)
        trace.consume(line)
        trace.consume(line)
        self.assertEqual(len(trace.events), 2)

    def test_missing_answer_id_is_not_fabricated_as_zero(self):
        trace = BoundaryTrace()
        event = trace.consume(record("service/rtcservice.go:620", "received answer", participantID="PEER", answer={"sdp": "SECRET"}))
        self.assertIsNotNone(event)
        self.assertNotIn("answer_id", event)

    def test_untrusted_timestamp_is_withheld(self):
        trace = BoundaryTrace()
        line = record("rtc/transport.go:2936", "processing answer", participantID="PEER", answerId=0).replace("2026-10-08T10:20:30.123Z", "SECRET_TIMESTAMP")
        self.assertIsNone(trace.consume(line))
        self.assertEqual(trace.health["malformed"], 1)

    def test_foreign_callers_and_unlisted_messages_are_ignored(self):
        trace = BoundaryTrace()
        for caller, message in [("foreign/transport.go:1", "processing answer"), ("rtc/transport.go:1", "received answer"), ("rtc/transport.go:1", "SECRET_MESSAGE")]:
            self.assertIsNone(trace.consume(record(caller, message, participantID="SECRET")))
        self.assertEqual(trace.events, [])
        self.assertEqual(trace.health["malformed"], 0)

    def test_malformed_relevant_input_is_withheld_and_counted(self):
        trace = BoundaryTrace()
        prefix = record("rtc/transport.go:2936", "processing answer", participantID="PEER").rsplit("\t", 1)[0]
        for fields in ["SECRET_BROKEN_JSON", "[]", json.dumps({"participantID": "PEER", "answerId": "SECRET"}), json.dumps({"participantID": "PEER", "answerId": True}), json.dumps({"participantID": "PEER", "answerId": -1}), json.dumps({"answerId": 0})]:
            self.assertIsNone(trace.consume(prefix + "\t" + fields))
        self.assertEqual(trace.health["malformed"], 6)
        self.assertEqual(trace.events, [])

    def test_peer_and_event_limits_prevent_partial_evidence_admission(self):
        trace = BoundaryTrace(max_peers=1, max_events=1)
        self.assertIsNotNone(trace.consume(record("rtc/transport.go:1", "processing answer", participantID="A", answerId=0)))
        self.assertIsNone(trace.consume(record("rtc/transport.go:1", "processing answer", participantID="B", answerId=0)))
        self.assertIsNone(trace.consume(record("rtc/transport.go:1", "processing answer", participantID="A", answerId=0)))
        self.assertEqual(trace.health["peer_limit"], 1)
        self.assertEqual(trace.health["event_limit"], 1)
        self.assertEqual(len(trace.events), 1)

    def test_oversized_lines_are_withheld(self):
        trace = BoundaryTrace()
        self.assertIsNone(trace.consume("SECRET" * 200000))
        self.assertEqual(trace.health["oversized"], 1)

    def test_timer_failure_is_fixed_category_and_has_no_raw_reason(self):
        trace = BoundaryTrace()
        for reason, category in [("negotiation timed out", "negotiation_timeout"), ("SECRET_FAILURE_SDP", "negotiation_failure")]:
            event = trace.consume(record("rtc/transport.go:100", "negotiation failed", participantID="PEER", reason=reason, localPending={"sdp": "SECRET"}))
            self.assertIsNotNone(event)
            self.assertEqual(event["stage"], category)
            self.assertEqual(set(event), {"time", "peer", "stage"})
        self.assertNotIn("SECRET", json.dumps(trace.events))


if __name__ == "__main__":
    unittest.main()
