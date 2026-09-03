import importlib.util
import json
import tempfile
import unittest
import base64
from copy import deepcopy
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


SCRIPT = Path(__file__).with_name("build-catalog.py")
SPEC = importlib.util.spec_from_file_location("build_catalog", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class BuildCatalogTest(unittest.TestCase):
    def write_spec(self, root: Path, manifest: dict) -> Path:
        manifest_path = root / "manifest.json"
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
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
        return spec_path

    def valid_manifest(self, routing_data_version: str = "valhalla-3.6.3") -> dict:
        artifact = {
            "url": "https://cdn.example.test/ekb/file",
            "downloadBytes": 10,
            "installedBytes": 20,
            "sha256": "0" * 64,
        }
        return {
            "schemaVersion": 2,
            "regionId": "ekb-agglomeration",
            "releaseVersion": "0.1.2",
            "createdAt": "2026-09-03T00:00:00Z",
            "source": {
                "osmReplicationSequence": 1,
                "osmTimestamp": "2026-09-03T00:00:00Z",
            },
            "compatibility": {
                "minAppVersionCode": 28,
                "routingEngine": "valhalla",
                "routingDataVersion": routing_data_version,
                "mapSchemaVersion": 1,
                "searchSchemaVersion": 1,
            },
            "coverage": {
                "bbox": [59.10, 56.00, 61.90, 57.55],
                "routingBufferKm": 20,
            },
            "components": {
                "routing": {**artifact, "compression": "gzip"},
                "search": {**artifact, "schemaVersion": 1},
                "map": {
                    **artifact,
                    "format": "pmtiles",
                    "minZoom": 5,
                    "maxZoom": 14,
                    "vectorLayerSchema": 1,
                },
            },
            "manifestSignature": {
                "keyId": "release-key",
                "algorithm": "ed25519",
                "value": "signature",
            },
        }

    def signed_manifest(self) -> tuple[dict, Ed25519PrivateKey]:
        key = Ed25519PrivateKey.generate()
        manifest = self.valid_manifest()
        unsigned = deepcopy(manifest)
        unsigned.pop("manifestSignature", None)
        manifest["manifestSignature"]["value"] = __import__("base64").b64encode(
            key.sign(MODULE.canonical_payload(unsigned))
        ).decode("ascii")
        return manifest, key

    def test_rejects_manifest_for_a_different_routing_data_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = self.valid_manifest("valhalla-3.8.3")
            spec_path = self.write_spec(root, manifest)

            with self.assertRaisesRegex(ValueError, "routingDataVersion"):
                MODULE.build_catalog(spec_path, generated_at="2026-09-03T00:00:00Z")

    def test_verifies_signature_against_the_publisher_key(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest, key = self.signed_manifest()
            spec_path = self.write_spec(root, manifest)
            public_key = key.public_key()

            catalog = MODULE.build_catalog(
                spec_path,
                generated_at="2026-09-03T00:00:00Z",
                public_key=public_key,
                expected_key_id="release-key",
                current_app_version_code=28,
            )

            self.assertEqual("ekb-agglomeration", catalog["regions"][0]["region"]["regionId"])

    def test_rejects_a_validly_shaped_manifest_with_the_wrong_signature(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = self.valid_manifest()
            manifest["manifestSignature"]["value"] = base64.b64encode(b"0" * 64).decode("ascii")
            spec_path = self.write_spec(root, manifest)

            with self.assertRaisesRegex(ValueError, "signature verification failed"):
                MODULE.build_catalog(
                    spec_path,
                    generated_at="2026-09-03T00:00:00Z",
                    public_key=Ed25519PrivateKey.generate().public_key(),
                    expected_key_id="release-key",
                    current_app_version_code=28,
                )

    def test_rejects_a_release_that_requires_a_newer_app(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest, key = self.signed_manifest()
            manifest["compatibility"]["minAppVersionCode"] = 29
            spec_path = self.write_spec(root, manifest)

            with self.assertRaisesRegex(ValueError, "newer app version"):
                MODULE.build_catalog(
                    spec_path,
                    generated_at="2026-09-03T00:00:00Z",
                    public_key=key.public_key(),
                    expected_key_id="release-key",
                    current_app_version_code=28,
                )

    def test_signs_the_catalog_and_keeps_the_key_aligned(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest, key = self.signed_manifest()
            spec_path = self.write_spec(root, manifest)
            catalog = MODULE.build_catalog(
                spec_path,
                generated_at="2026-09-03T00:00:00Z",
                public_key=key.public_key(),
                expected_key_id="release-key",
                current_app_version_code=28,
            )

            signed = MODULE.sign_catalog(catalog, key, "release-key")
            signature = base64.b64decode(signed["catalogSignature"]["value"])
            key.public_key().verify(signature, MODULE.canonical_catalog_payload(signed))
            self.assertEqual("release-key", signed["catalogSignature"]["keyId"])

    def test_catalog_signing_rejects_a_development_key_id(self):
        with self.assertRaisesRegex(ValueError, "production key"):
            MODULE.sign_catalog({}, Ed25519PrivateKey.generate(), "UNSIGNED_DEV")


if __name__ == "__main__":
    unittest.main()
