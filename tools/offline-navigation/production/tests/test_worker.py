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
                "regionId": "g1-146-240",
                "coverage": {"bbox": [60.0, 56.0, 61.0, 57.0]},
            }), encoding="utf-8")

            config = load_config(config_path)

            with patch("production.worker.subprocess.run"):
                Worker(config, root / "queue.json")._write_catalog()

            spec = json.loads((root / "staging" / "catalog-spec.json").read_text(encoding="utf-8"))
            self.assertEqual("Екатеринбург", spec["regions"][0]["displayName"])

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


if __name__ == "__main__":
    unittest.main()
