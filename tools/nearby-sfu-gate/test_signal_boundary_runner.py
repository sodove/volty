import importlib.util
import io
import json
import os
import pathlib
import tempfile
import threading
import unittest

spec = importlib.util.spec_from_file_location("boundary_runner", pathlib.Path(__file__).with_name("run-signal-boundary-probe.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class BoundaryRunnerTest(unittest.TestCase):
    def earned_trace(self):
        trace = runner.module.BoundaryTrace()
        for peer in ("A", "B", "C"):
            for caller, message in (("service/rtcservice.go:1", "received answer"), ("rtc/participant.go:1", "received answer"), ("rtc/transport.go:1", "processing answer")):
                line = "2026-10-08T10:20:30Z\tDEBUG\tlivekit\t" + caller + "\t" + message + "\t" + json.dumps({"participantID": peer, "answerId": 0})
                self.assertIsNotNone(trace.consume(line))
        return trace

    def test_persisted_result_records_actual_attach_status_and_acceptance(self):
        result = runner.boundary_result(self.earned_trace(), {"read_error": 0, "premature_eof": 0}, 0, 0)
        self.assertTrue(result["accepted"])
        self.assertEqual(result.get("attach_exit"), 0)

    def test_persisted_result_denies_failed_attach_capture_probe_or_partial_peers(self):
        for fault in ("attach", "capture", "probe", "peers"):
            with self.subTest(fault=fault):
                trace = self.earned_trace()
                if fault == "capture":
                    trace.health["malformed"] = 1
                if fault == "peers":
                    trace.events = [e for e in trace.events if e["peer"] != 3]
                result = runner.boundary_result(trace, {"read_error": 0, "premature_eof": 0}, 1 if fault == "probe" else 0, 1 if fault == "attach" else 0)
                self.assertFalse(result["accepted"])

    def test_eof_before_owned_stop_is_incomplete_capture(self):
        trace = runner.module.BoundaryTrace()
        health = {"read_error": 0, "premature_eof": 0}
        runner.read_trace(io.BytesIO(b""), trace, threading.Event(), health)
        self.assertEqual(health["premature_eof"], 1)

    def test_eof_after_owned_stop_is_normal(self):
        health = {"read_error": 0, "premature_eof": 0}
        stopping = threading.Event()
        stopping.set()
        runner.read_trace(io.BytesIO(b""), runner.module.BoundaryTrace(), stopping, health)
        self.assertEqual(health, {"read_error": 0, "premature_eof": 0})

    def test_oversized_record_is_drained_without_joining_raw_chunks(self):
        trace = runner.module.BoundaryTrace()
        health = {"read_error": 0, "premature_eof": 0}
        line = b"SECRET" * 200000 + b"\n"
        runner.read_trace(io.BytesIO(line), trace, threading.Event(), health)
        self.assertEqual(trace.health["oversized"], 1)
        self.assertEqual(trace.events, [])
        self.assertEqual(health["premature_eof"], 1)

    def test_invalid_utf8_withheld(self):
        health = {"read_error": 0, "premature_eof": 0}
        runner.read_trace(io.BytesIO(b"\xffSECRET\n"), runner.module.BoundaryTrace(), threading.Event(), health)
        self.assertEqual(health["read_error"], 1)

    def test_directory_in_place_of_regular_config_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            work = pathlib.Path(directory)
            for name in ("probe", "signal-boundary.py", "run-signal-boundary-probe.py"):
                (work / name).write_text("fixture")
            (work / "probe.yaml").mkdir()
            with self.assertRaisesRegex(RuntimeError, "regular owned files"):
                runner.validate_workspace(work)

    def test_symlink_config_cannot_rewrite_external_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            work = root / "owned"
            work.mkdir()
            external = root / "external.yaml"
            external.write_text("level: warn")
            for name in ("probe", "signal-boundary.py", "run-signal-boundary-probe.py"):
                (work / name).write_text("fixture")
            try:
                (work / "probe.yaml").symlink_to(external)
            except OSError:
                self.skipTest("Native symlink creation unavailable; run on Linux too")
            with self.assertRaisesRegex(RuntimeError, "regular owned files"):
                runner.validate_workspace(work)
            self.assertEqual(external.read_text(), "level: warn")

    def test_hardlinked_config_cannot_rewrite_external_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            work = root / "owned"
            work.mkdir()
            external = root / "external.yaml"
            external.write_text("level: warn")
            for name in ("probe", "signal-boundary.py", "run-signal-boundary-probe.py"):
                (work / name).write_text("fixture")
            os.link(external, work / "probe.yaml")
            with self.assertRaisesRegex(RuntimeError, "regular owned files"):
                runner.validate_workspace(work)
            self.assertEqual(external.read_text(), "level: warn")


if __name__ == "__main__":
    unittest.main()
