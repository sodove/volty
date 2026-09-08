import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from production.config import load_config
from production.scheduler import Scheduler
from production.worker import Worker


class WorkerTest(unittest.TestCase):
    def test_request_build_schedules_one_navigation_job(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = root / "config.json"
            config_path.write_text(json.dumps({
                "publicRoot": str(root / "public"),
                "stagingRoot": str(root / "staging"),
                "sourceRoot": str(root / "sources"),
                "signingKey": str(root / "keys" / "signing-key.pem"),
                "regions": [{"id": "region", "sourceId": "russia",
                             "sourceUrl": "https://download.example/region.pbf"}],
            }), encoding="utf-8")
            config = load_config(config_path)
            config.source_root.mkdir(parents=True)
            (config.source_root / "russia.source.json").write_text(json.dumps({
                "osmSequence": 42,
                "osmTimestamp": "2026-09-07T00:00:00Z",
                "geometryHash": "a" * 64,
            }), encoding="utf-8")
            worker = Worker(config, root / "queue.json")

            first = worker.request_build("region")
            second = worker.request_build("region")

            self.assertEqual("queued", first["status"])
            self.assertEqual(first["requestId"], second["requestId"])
            jobs = json.loads((root / "queue.json").read_text(encoding="utf-8"))["jobs"]
            self.assertEqual(1, len(jobs))
            self.assertEqual("navigation", jobs[0]["kind"])

    def test_region_display_name_is_loaded_from_config(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = root / "config.json"
            config_path.write_text(json.dumps({
                "publicRoot": str(root / "public"),
                "stagingRoot": str(root / "staging"),
                "sourceRoot": str(root / "sources"),
                "signingKey": str(root / "keys" / "signing-key.pem"),
                "regions": [{
                    "id": "g1-146-240",
                    "displayName": "Екатеринбург",
                    "sourceUrl": "https://download.example/region.pbf",
                }],
            }), encoding="utf-8")

            config = load_config(config_path)

            self.assertEqual("Екатеринбург", config.regions[0].display_name)

    def test_catalog_spec_uses_region_display_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = root / "config.json"
            key_path = root / "keys" / "signing-key.pem"
            key_path.parent.mkdir()
            key_path.write_bytes(Ed25519PrivateKey.generate().private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            ))
            config_path.write_text(json.dumps({
                "publicRoot": str(root / "public"),
                "stagingRoot": str(root / "staging"),
                "sourceRoot": str(root / "sources"),
                "signingKey": str(key_path),
                "regions": [{
                    "id": "g1-146-240",
                    "displayName": "Екатеринбург",
                    "sourceUrl": "https://download.example/region.pbf",
                }],
            }), encoding="utf-8")
            manifest_path = root / "public" / "regions" / "g1-146-240" / "2026-09-06" / "manifest.json"
            manifest_path.parent.mkdir(parents=True)
            manifest_path.write_text(json.dumps({
                "schemaVersion": 3,
                "regionId": "g1-146-240",
                "coverage": {"bbox": [60.0, 56.0, 61.0, 57.0]},
                "components": {"routing": {}, "search": {}},
            }), encoding="utf-8")

            config = load_config(config_path)

            with patch("production.worker.subprocess.run"):
                Worker(config, root / "queue.json")._write_catalog()

            spec = json.loads((root / "staging" / "catalog-spec.json").read_text(encoding="utf-8"))
            self.assertEqual("Екатеринбург", spec["regions"][0]["displayName"])

    def test_catalog_writer_skips_retained_legacy_manifests(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            key_path = root / "keys" / "signing-key.pem"
            key_path.parent.mkdir()
            key_path.write_bytes(Ed25519PrivateKey.generate().private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            ))
            config_path = root / "config.json"
            config_path.write_text(json.dumps({
                "publicRoot": str(root / "public"),
                "stagingRoot": str(root / "staging"),
                "sourceRoot": str(root / "sources"),
                "signingKey": str(key_path),
                "regions": [{
                    "id": "g1-146-240",
                    "displayName": "Екатеринбург",
                    "bbox": "60.0,56.0,61.0,57.0",
                    "sourceUrl": "https://download.example/region.pbf",
                }],
            }), encoding="utf-8")
            legacy = root / "public" / "regions" / "g1-146-240" / "2026-09-06" / "manifest.json"
            legacy.parent.mkdir(parents=True)
            legacy.write_text(json.dumps({
                "schemaVersion": 2,
                "regionId": "g1-146-240",
                "components": {"routing": {}, "search": {}, "map": {}},
            }), encoding="utf-8")

            config = load_config(config_path)
            with patch("production.worker.subprocess.run"):
                Worker(config, root / "queue.json")._write_catalog()

            spec = json.loads((root / "staging" / "catalog-spec.json").read_text(encoding="utf-8"))
            self.assertNotIn("manifest", spec["regions"][0])
            self.assertEqual({"enabled": True}, spec["regions"][0]["onDemand"])

    def test_worker_does_not_fabricate_source_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = root / "config.json"
            config_path.write_text(json.dumps({
                "publicRoot": str(root / "public"),
                "stagingRoot": str(root / "staging"),
                "sourceRoot": str(root / "sources"),
                "signingKey": str(root / "keys" / "signing-key.pem"),
                "regions": [{"id": "region", "sourceId": "russia", "sourceUrl": "https://download.example/region.pbf"}],
            }), encoding="utf-8")
            config = load_config(config_path)
            queue = root / "queue.json"
            Scheduler(config, queue).tick(1760000000)
            queued = json.loads(queue.read_text(encoding="utf-8"))["jobs"][0]
            self.assertEqual("russia", queued["sourceId"])
            self.assertFalse(Worker(config, queue).run_once())
            jobs = json.loads(queue.read_text(encoding="utf-8"))["jobs"]
            self.assertEqual("failed", jobs[0]["state"])
            self.assertEqual("source_metadata_required", jobs[0]["reason"])

    def test_on_demand_only_worker_leaves_legacy_jobs_queued(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = root / "config.json"
            config_path.write_text(json.dumps({
                "publicRoot": str(root / "public"),
                "stagingRoot": str(root / "staging"),
                "sourceRoot": str(root / "sources"),
                "signingKey": str(root / "keys" / "signing-key.pem"),
                "regions": [{"id": "region", "sourceId": "russia",
                             "sourceUrl": "https://download.example/region.pbf"}],
            }), encoding="utf-8")
            config = load_config(config_path)
            queue = root / "queue.json"
            queue.write_text(json.dumps({"jobs": [
                {"id": "legacy", "regionId": "region", "state": "queued"},
                {"id": "requested", "regionId": "region", "onDemand": True, "state": "queued"},
            ]}), encoding="utf-8")

            self.assertFalse(Worker(config, queue).run_once(on_demand_only=True))
            jobs = json.loads(queue.read_text(encoding="utf-8"))["jobs"]
            self.assertEqual("queued", jobs[0]["state"])
            self.assertEqual("failed", jobs[1]["state"])
            self.assertEqual("source_metadata_required", jobs[1]["reason"])

    def test_request_build_does_not_reuse_scheduler_job(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = root / "config.json"
            config_path.write_text(json.dumps({
                "publicRoot": str(root / "public"),
                "stagingRoot": str(root / "staging"),
                "sourceRoot": str(root / "sources"),
                "signingKey": str(root / "keys" / "signing-key.pem"),
                "regions": [{"id": "region", "sourceId": "russia",
                             "sourceUrl": "https://download.example/region.pbf"}],
            }), encoding="utf-8")
            config = load_config(config_path)
            config.source_root.mkdir(parents=True)
            (config.source_root / "russia.source.json").write_text(json.dumps({
                "osmSequence": 42,
                "osmTimestamp": "2026-09-07T00:00:00Z",
                "geometryHash": "a" * 64,
            }), encoding="utf-8")
            queue = root / "queue.json"
            queue.write_text(json.dumps({"jobs": [{
                "id": "scheduled", "regionId": "region", "sourceId": "russia", "state": "queued",
            }]}), encoding="utf-8")

            result = Worker(config, queue).request_build("region")

            self.assertEqual("queued", result["status"])
            self.assertNotEqual("scheduled", result["requestId"])
            jobs = json.loads(queue.read_text(encoding="utf-8"))["jobs"]
            self.assertEqual(2, len(jobs))
            self.assertTrue(jobs[1]["onDemand"])


if __name__ == "__main__":
    unittest.main()
