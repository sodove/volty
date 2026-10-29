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
    def test_grid_region_name_uses_source_metadata_without_user_input(self):
        self.assertEqual(
            "Уральский федеральный округ",
            Worker._fallback_display_name("g1-146-240", [60.0, 56.0, 61.0, 57.0], "ural-fed-district"),
        )

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

    def _compact_fixture(self, root: Path, compact):
        key_path = root / "keys" / "signing-key.pem"
        key_path.parent.mkdir()
        key_path.write_bytes(Ed25519PrivateKey.generate().private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ))
        raw = {
            "publicRoot": str(root / "public"),
            "stagingRoot": str(root / "staging"),
            "sourceRoot": str(root / "sources"),
            "signingKey": str(key_path),
            "regions": [
                {"id": "g1-146-240", "displayName": "Екатеринбург", "bbox": "60.0,56.0,61.0,57.0",
                 "sourceUrl": "https://download.example/region.pbf"},
                {"id": "g1-147-240", "bbox": "60.0,57.0,61.0,58.0", "sourceUrl": "https://download.example/region.pbf"},
                {"id": "g1-148-240", "bbox": "60.0,58.0,61.0,59.0", "sourceUrl": "https://download.example/region.pbf"},
            ],
        }
        if compact is not None:
            raw["compactCatalog"] = compact
        config_path = root / "config.json"
        config_path.write_text(json.dumps(raw), encoding="utf-8")
        manifest_path = root / "public" / "regions" / "g1-146-240" / "2026-09-06" / "manifest.json"
        manifest_path.parent.mkdir(parents=True)
        manifest_path.write_text(json.dumps({
            "schemaVersion": 3, "regionId": "g1-146-240",
            "coverage": {"bbox": [60.0, 56.0, 61.0, 57.0]},
            "components": {"routing": {}, "search": {}},
        }), encoding="utf-8")
        return load_config(config_path)

    def test_compact_catalog_publishes_only_built_releases_and_keeps_every_cell_in_the_index(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = self._compact_fixture(root, True)
            self.assertTrue(config.compact_catalog)
            with patch("production.worker.subprocess.run") as run:
                Worker(config, root / "queue.json")._write_catalog()

            outputs = [call.args[0][call.args[0].index("--output") + 1] for call in run.call_args_list]
            # Index first: a reader that sees the compact catalog has already seen the index.
            self.assertEqual(["out-catalog-index.json", "out-catalog.json"], [Path(o).name for o in outputs])
            index = json.loads((root / "staging" / "catalog-spec-index.json").read_text(encoding="utf-8"))
            client = json.loads((root / "staging" / "catalog-spec.json").read_text(encoding="utf-8"))
            self.assertEqual(3, len(index["regions"]))
            self.assertEqual(["g1-146-240"], [r["regionId"] for r in client["regions"]])
            self.assertTrue(all("manifest" in r for r in client["regions"]))

    def test_catalog_stays_complete_by_default_and_drops_a_stale_index(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = self._compact_fixture(root, None)
            self.assertFalse(config.compact_catalog)
            stale = root / "public" / "catalog-index.json"
            stale.write_text("{}", encoding="utf-8")
            with patch("production.worker.subprocess.run") as run:
                Worker(config, root / "queue.json")._write_catalog()

            self.assertEqual(1, run.call_count)
            client = json.loads((root / "staging" / "catalog-spec.json").read_text(encoding="utf-8"))
            self.assertEqual(3, len(client["regions"]))
            self.assertFalse(stale.exists())

    def test_compact_catalog_with_nothing_built_falls_back_to_the_complete_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = self._compact_fixture(root, True)
            (root / "public" / "regions" / "g1-146-240" / "2026-09-06" / "manifest.json").unlink()
            with patch("production.worker.subprocess.run") as run:
                Worker(config, root / "queue.json")._write_catalog()

            self.assertEqual(1, run.call_count)  # build-catalog.py refuses an empty region list
            client = json.loads((root / "staging" / "catalog-spec.json").read_text(encoding="utf-8"))
            self.assertEqual(3, len(client["regions"]))

    def test_oversized_catalog_is_not_published_and_the_previous_one_survives(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = self._compact_fixture(root, None)
            self.assertEqual(4 * 1024 * 1024, config.max_catalog_bytes)
            published = root / "public" / "catalog.json"
            published.write_text("previous", encoding="utf-8")

            def fake_builder(command, **_):
                target = Path(command[command.index("--output") + 1])
                target.write_bytes(b"x" * (config.max_catalog_bytes + 1))

            with patch("production.worker.subprocess.run", side_effect=fake_builder):
                with self.assertRaises(ValueError):
                    Worker(config, root / "queue.json")._write_catalog()

            self.assertEqual("previous", published.read_text(encoding="utf-8"))

    def test_catalog_within_the_limit_replaces_the_published_one(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = self._compact_fixture(root, None)

            def fake_builder(command, **_):
                Path(command[command.index("--output") + 1]).write_bytes(b"{}")

            with patch("production.worker.subprocess.run", side_effect=fake_builder):
                Worker(config, root / "queue.json")._write_catalog()

            self.assertEqual(b"{}", (root / "public" / "catalog.json").read_bytes())

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

    def test_build_status_does_not_report_stale_scheduler_job(self):
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
            queue.write_text(json.dumps({"jobs": [{
                "id": "scheduled", "regionId": "region", "sourceId": "russia", "state": "queued",
            }]}), encoding="utf-8")

            result = Worker(config, queue).build_status("region")

            self.assertEqual("unavailable", result["status"])
            self.assertEqual("build_not_requested", result["errorCode"])


if __name__ == "__main__":
    unittest.main()
