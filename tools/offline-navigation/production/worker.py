"""Persistent single-slot source -> package -> signed publication worker."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from .config import ProductionConfig, load_config
from .source_cache import SourceError, fetch_snapshot


class Worker:
    def __init__(self, config: ProductionConfig, queue_path: Path):
        self.config = config
        self.queue_path = queue_path.resolve()
        self.signer = Path(__file__).parents[1] / "sign-manifest.py"
        self.verifier = Path(__file__).parents[1] / "verify-package.py"

    def _read(self) -> dict:
        if not self.queue_path.exists():
            return {"jobs": []}
        value = json.loads(self.queue_path.read_text(encoding="utf-8"))
        if not isinstance(value, dict) or not isinstance(value.get("jobs", []), list):
            raise ValueError("queue file is malformed")
        return value

    def _write(self, value: dict) -> None:
        self.queue_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.queue_path.with_suffix(self.queue_path.suffix + ".tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
        os.replace(temporary, self.queue_path)

    def _fail(self, state: dict, job: dict, reason: str) -> bool:
        job.update(state="failed", reason=reason)
        self._write(state)
        return False

    def run_once(self) -> bool:
        state = self._read()
        job = next((item for item in state["jobs"] if isinstance(item, dict) and item.get("state") == "queued"), None)
        if job is None:
            return False
        job["state"] = "running"
        job["attempt"] = int(job.get("attempt", 0)) + 1
        self._write(state)
        metadata_path = self.config.source_root / f"{job['regionId']}.source.json"
        if not metadata_path.is_file():
            return self._fail(state, job, "source_metadata_required")
        try:
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            sequence = metadata["osmSequence"]
            timestamp = metadata["osmTimestamp"]
            geometry_hash = metadata["geometryHash"]
            if (isinstance(sequence, bool) or not isinstance(sequence, int) or sequence < 0 or
                    not isinstance(timestamp, str) or not timestamp or not isinstance(geometry_hash, str) or not geometry_hash):
                raise ValueError("source_metadata_required")
        except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
            return self._fail(state, job, str(error) or "source_metadata_required")

        if self.config.build_script is None or not self.config.build_script.is_file():
            return self._fail(state, job, "build_script_required")
        attempt = self.config.staging_root / f"{job['id']}-{job['attempt']}"
        package = attempt / "package"
        try:
            attempt.mkdir(parents=True, exist_ok=False)
            snapshot = fetch_snapshot(job["regionId"], job["sourceUrl"], self.config.source_root,
                                      max_bytes=self.config.max_download_bytes, osm_timestamp=timestamp,
                                      replication_sequence=sequence, geometry_hash=geometry_hash)
            args = ["bash", str(self.config.build_script), str(self.config.source_root / f"{job['regionId']}.osm.pbf"),
                    str(package), "--region-id", job["regionId"], "--release-version", job["id"],
                    "--min-app-version-code", str(self.config.min_app_version_code), "--osm-sequence", str(sequence),
                    "--osm-timestamp", timestamp, "--base-url", self.config.public_base_url]
            if job.get("bbox"):
                args += ["--bbox", job["bbox"]]
            subprocess.run(args, cwd=self.config.build_script.parent, check=True, timeout=self.config.max_runtime_seconds)
            subprocess.run([sys.executable, str(self.verifier), str(package)], check=True,
                           timeout=300)
            signed = attempt / "manifest.json"
            subprocess.run([sys.executable, str(self.signer), str(package / "manifest.unsigned.json"), str(signed),
                            "--private-key", str(self.config.signing_key), "--key-id", self.config.key_id],
                           check=True, timeout=60)
            (package / "manifest.unsigned.json").unlink()
            self._publish(job, package, signed)
            job.update(state="ready", sourceSha256=snapshot.sha256, completedAt=time.time())
            self._write(state)
            return True
        except (OSError, SourceError, subprocess.SubprocessError, ValueError, KeyError) as error:
            return self._fail(state, job, type(error).__name__ + ": " + str(error))
        finally:
            shutil.rmtree(attempt, ignore_errors=True)

    def _publish(self, job: dict, package: Path, signed: Path) -> None:
        destination = self.config.public_root / "regions" / job["regionId"] / job["id"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            existing = destination / "manifest.json"
            if existing.read_bytes() == signed.read_bytes():
                self._write_catalog()
                return
            raise ValueError("immutable_release_conflict")
        temporary = destination.with_name("." + destination.name + ".publishing")
        shutil.copytree(package, temporary)
        manifest_bytes = (temporary / "manifest.json").read_bytes()
        (temporary / ".ready.json").write_text(json.dumps({
            "manifestSha256": hashlib.sha256(manifest_bytes).hexdigest()
        }) + "\n", encoding="utf-8")
        os.replace(temporary, destination)
        self._write_catalog()

    def _write_catalog(self) -> None:
        manifests = list((self.config.public_root / "regions").glob("*/*/manifest.json"))
        if not manifests:
            return
        entries = []
        by_id = {region.id: region for region in self.config.regions}
        for manifest_path in manifests:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            region = by_id.get(manifest.get("regionId"))
            if region is None:
                continue
            bbox = [float(v) for v in region.bbox.split(",")] if region.bbox else manifest["coverage"]["bbox"]
            entries.append({"regionId": region.id, "displayName": region.id, "bounds":
                            {"west": bbox[0], "south": bbox[1], "east": bbox[2], "north": bbox[3]},
                            "manifest": str(manifest_path)})
        spec = self.config.staging_root / "catalog-spec.json"
        spec.parent.mkdir(parents=True, exist_ok=True)
        spec.write_text(json.dumps({"regions": entries}, ensure_ascii=False), encoding="utf-8")
        public_key = self.config.staging_root / "public-key.txt"
        key = serialization.load_pem_private_key(self.config.signing_key.read_bytes(), password=None)
        if not isinstance(key, Ed25519PrivateKey):
            raise ValueError("signing key must be Ed25519")
        public_key.write_text(base64.b64encode(key.public_key().public_bytes(serialization.Encoding.Raw,
                                                                              serialization.PublicFormat.Raw)).decode(), encoding="ascii")
        output = self.config.public_root / "catalog.json"
        subprocess.run([sys.executable, str(Path(__file__).parents[1] / "build-catalog.py"), "--spec", str(spec),
                        "--output", str(output), "--public-key", str(public_key), "--private-key",
                        str(self.config.signing_key), "--key-id", self.config.key_id,
                        "--current-app-version-code", str(self.config.min_app_version_code)], check=True, timeout=60)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--queue", type=Path, required=True)
    args = parser.parse_args()
    config = load_config(args.config)
    stop = False
    def handle_signal(_signum, _frame):
        nonlocal stop
        stop = True
    signal.signal(signal.SIGTERM, handle_signal)
    signal.signal(signal.SIGINT, handle_signal)
    worker = Worker(config, args.queue)
    while not stop:
        worker.run_once()
        time.sleep(config.poll_seconds)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
