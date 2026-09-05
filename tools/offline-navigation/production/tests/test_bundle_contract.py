import json
import tempfile
import unittest
from pathlib import Path

from production.config import ConfigError, load_config
from production.scheduler import Scheduler


class BundleContractTest(unittest.TestCase):
    def test_config_rejects_private_key_inside_public_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "config.json"
            config.write_text(json.dumps({
                "publicRoot": str(root / "public"),
                "stagingRoot": str(root / "staging"),
                "sourceRoot": str(root / "sources"),
                "signingKey": str(root / "public" / "signing-key.pem"),
                "regions": [],
            }), encoding="utf-8")
            with self.assertRaises(ConfigError):
                load_config(config)

    def test_config_rejects_placeholder_signing_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "config.json"
            config.write_text(json.dumps({
                "publicRoot": str(root / "public"),
                "stagingRoot": str(root / "staging"),
                "sourceRoot": str(root / "sources"),
                "signingKey": str(root / "keys" / "signing-key.pem"),
                "keyId": "REPLACE_WITH_EXISTING_PRODUCTION_KEY_ID",
                "regions": [],
            }), encoding="utf-8")
            with self.assertRaises(ConfigError):
                load_config(config)

    def test_scheduler_tick_is_idempotent_for_same_fingerprint(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "config.json"
            config.write_text(json.dumps({
                "publicRoot": str(root / "public"),
                "stagingRoot": str(root / "staging"),
                "sourceRoot": str(root / "sources"),
                "signingKey": str(root / "keys" / "signing-key.pem"),
                "regions": [{"id": "g1-056-060", "sourceUrl": "https://download.example/russia.osm.pbf"}],
            }), encoding="utf-8")
            settings = load_config(config)
            scheduler = Scheduler(settings, root / "queue.json")
            first = scheduler.tick(1760000000)
            second = scheduler.tick(1760000000)
            self.assertEqual(1, len(first))
            self.assertEqual([], second)
            self.assertEqual(first[0], json.loads((root / "queue.json").read_text())["jobs"][0]["id"])


if __name__ == "__main__":
    unittest.main()
