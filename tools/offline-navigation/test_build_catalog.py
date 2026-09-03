import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("build-catalog.py")
SPEC = importlib.util.spec_from_file_location("build_catalog", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class BuildCatalogTest(unittest.TestCase):
    def test_rejects_manifest_for_a_different_routing_data_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = {
                "regionId": "ekb-agglomeration",
                "releaseVersion": "0.1.2",
                "manifestSignature": {
                    "keyId": "release-key",
                    "algorithm": "ed25519",
                    "value": "signature",
                },
                "compatibility": {"routingDataVersion": "valhalla-3.8.3"},
                "coverage": {"bbox": [59.10, 56.00, 61.90, 57.55]},
            }
            manifest_path = root / "manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            spec_path = root / "regions.json"
            spec_path.write_text(
                json.dumps(
                    {
                        "regions": [
                            {
                                "regionId": "ekb-agglomeration",
                                "displayName": "Yekaterinburg",
                                "bounds": [59.10, 56.00, 61.90, 57.55],
                                "manifest": "manifest.json",
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "routingDataVersion"):
                MODULE.build_catalog(spec_path, generated_at="2026-09-03T00:00:00Z")


if __name__ == "__main__":
    unittest.main()
