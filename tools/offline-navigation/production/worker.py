"""Persistent single-slot source -> package -> signed publication worker."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from .config import ProductionConfig, load_config
from .source_cache import SourceError, fetch_snapshot


class Worker:
    def __init__(self, config: ProductionConfig, queue_path: Path):
        self.config = config
        self.queue_path = queue_path.resolve()
        self._queue_lock = threading.RLock()
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

    def _fail(self, job: dict, reason: str) -> bool:
        with self._queue_lock:
            state = self._read()
            current = next((item for item in state["jobs"]
                            if isinstance(item, dict) and item.get("id") == job.get("id")), None)
            if current is None:
                return False
            current.update(state="failed", reason=reason)
            self._write(state)
        return False

    def run_once(self, on_demand_only: bool = False) -> bool:
        with self._queue_lock:
            state = self._read()
            job = next((item for item in state["jobs"]
                        if isinstance(item, dict) and item.get("state") == "queued"
                        and (not on_demand_only or item.get("onDemand") is True)), None)
            if job is None:
                return False
            job["state"] = "running"
            job["attempt"] = int(job.get("attempt", 0)) + 1
            self._write(state)
        source_id = job.get("sourceId") or job["regionId"]
        if not isinstance(source_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", source_id):
            return self._fail(job, "source_id_invalid")
        metadata_path = self.config.source_root / f"{source_id}.source.json"
        if not metadata_path.is_file():
            return self._fail(job, "source_metadata_required")
        try:
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            sequence = metadata["osmSequence"]
            timestamp = metadata["osmTimestamp"]
            geometry_hash = metadata["geometryHash"]
            if (isinstance(sequence, bool) or not isinstance(sequence, int) or sequence < 0 or
                    not isinstance(timestamp, str) or not timestamp or not isinstance(geometry_hash, str) or not geometry_hash):
                raise ValueError("source_metadata_required")
        except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
            return self._fail(job, str(error) or "source_metadata_required")

        if self.config.build_script is None or not self.config.build_script.is_file():
            return self._fail(job, "build_script_required")
        attempt = self.config.staging_root / f"{job['id']}-{job['attempt']}"
        package = attempt / "package"
        try:
            attempt.mkdir(parents=True, exist_ok=False)
            snapshot = fetch_snapshot(source_id, job["sourceUrl"], self.config.source_root,
                                      max_bytes=self.config.max_download_bytes, osm_timestamp=timestamp,
                                      replication_sequence=sequence, geometry_hash=geometry_hash)
            args = ["bash", str(self.config.build_script), str(self.config.source_root / f"{source_id}.osm.pbf"),
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
            with self._queue_lock:
                state = self._read()
                current = next((item for item in state["jobs"]
                                if isinstance(item, dict) and item.get("id") == job.get("id")), None)
                if current is None:
                    return False
                current.update(state="ready", sourceSha256=snapshot.sha256, completedAt=time.time())
                self._write(state)
            return True
        except (OSError, SourceError, subprocess.SubprocessError, ValueError, KeyError) as error:
            return self._fail(job, type(error).__name__ + ": " + str(error))
        finally:
            shutil.rmtree(attempt, ignore_errors=True)

    def request_build(self, region_id: str) -> dict:
        region = next((item for item in self.config.regions if item.id == region_id), None)
        if region is None:
            return {"status": "unavailable", "regionId": region_id, "releaseVersion": None,
                    "errorCode": "region_unavailable"}
        source_id = region.source_id or region.id
        metadata_path = self.config.source_root / f"{source_id}.source.json"
        if not metadata_path.is_file():
            return {"status": "unavailable", "regionId": region_id, "releaseVersion": None,
                    "errorCode": "source_metadata_required"}
        with self._queue_lock:
            state = self._read()
            matching = [job for job in state["jobs"]
                        if isinstance(job, dict) and job.get("regionId") == region_id]
            for job in reversed(matching):
                if job.get("state") in {"queued", "running"}:
                    return self._job_status(job)
            request_id = f"{region_id}-{int(time.time())}"
            job = {
                "id": request_id,
                "requestId": request_id,
                "regionId": region.id,
                "sourceId": source_id,
                "sourceUrl": region.source_url,
                "bbox": region.bbox,
                "state": "queued",
                "onDemand": True,
            }
            state["jobs"].append(job)
            self._write(state)
            return self._job_status(job)

    def build_status(self, region_id: str) -> dict:
        with self._queue_lock:
            state = self._read()
            matching = [job for job in state["jobs"]
                        if isinstance(job, dict) and job.get("regionId") == region_id]
            for job in reversed(matching):
                if job.get("state") in {"queued", "running", "ready", "failed"}:
                    return self._job_status(job)
            return {"status": "unavailable", "regionId": region_id, "releaseVersion": None,
                    "errorCode": "build_not_requested"}

    @staticmethod
    def _job_status(job: dict) -> dict:
        state = job.get("state")
        status = {"queued": "queued", "running": "building", "ready": "ready", "failed": "failed"}.get(state, "unavailable")
        value = {"status": status, "regionId": job.get("regionId"),
                 "requestId": job.get("requestId", job.get("id")),
                 "releaseVersion": job.get("id") if state == "ready" else None}
        if state == "failed":
            value["errorCode"] = job.get("reason", "build_failed")
            value["retryAfterSeconds"] = 30
        elif state in {"queued", "running"}:
            value["retryAfterSeconds"] = 5
        return value

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
        # The builder produces an unsigned manifest inside the package and the
        # signer writes the verified manifest alongside the package. Publish
        # only the signed manifest; never expose the unsigned one.
        shutil.copy2(signed, temporary / "manifest.json")
        manifest_bytes = (temporary / "manifest.json").read_bytes()
        (temporary / ".ready.json").write_text(json.dumps({
            "manifestSha256": hashlib.sha256(manifest_bytes).hexdigest()
        }) + "\n", encoding="utf-8")
        # The worker deliberately runs as an unprivileged UID, while the app
        # container uses a different UID. Public artifacts must therefore be
        # traversable/readable by the app without making staging or secrets
        # world-readable.
        for path in temporary.rglob("*"):
            path.chmod(0o755 if path.is_dir() else 0o644)
        temporary.chmod(0o755)
        os.replace(temporary, destination)
        self._write_catalog()

    def _write_catalog(self) -> None:
        manifests = list((self.config.public_root / "regions").glob("*/*/manifest.json"))
        if not manifests and not self.config.regions:
            return
        existing_names = {}
        existing_catalog = self.config.public_root / "catalog.json"
        if existing_catalog.is_file():
            try:
                previous = json.loads(existing_catalog.read_text(encoding="utf-8"))
                existing_names = {
                    item["region"]["regionId"]: item["region"].get("displayName")
                    for item in previous.get("regions", [])
                    if isinstance(item, dict) and isinstance(item.get("region"), dict)
                    and item["region"].get("regionId") and item["region"].get("displayName")
                }
            except (OSError, ValueError, KeyError, TypeError):
                existing_names = {}
        published = {}
        for manifest_path in manifests:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            published[manifest.get("regionId")] = (manifest, manifest_path)
        entries = []
        for region in sorted(self.config.regions, key=lambda item: (item.display_name or item.id, item.id)):
            published_item = published.get(region.id)
            manifest = published_item[0] if published_item else None
            manifest_path = published_item[1] if published_item else None
            if region.bbox:
                bbox = [float(v) for v in region.bbox.split(",")]
            elif manifest:
                bbox = manifest["coverage"]["bbox"]
            else:
                # A null-release entry must still have a logical envelope in
                # production.json; refuse to sign an ambiguous catalog.
                continue
            display_name = region.display_name or existing_names.get(region.id) or self._fallback_display_name(region.id)
            entry = {"regionId": region.id, "displayName": display_name,
                     "bounds": bbox, "onDemand": {"enabled": manifest is None}}
            if manifest_path is not None:
                entry["manifest"] = str(manifest_path)
            entries.append(entry)
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

    @staticmethod
    def _fallback_display_name(region_id: str) -> str:
        match = re.fullmatch(r"g1-(\d+)-(\d+)", region_id)
        return f"Регион {match.group(1)}–{match.group(2)}" if match else region_id


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--queue", type=Path, required=True)
    parser.add_argument("--control-host", default="127.0.0.1")
    parser.add_argument("--control-port", type=int, default=int(os.environ.get("VOLTY_OFFLINE_CONTROL_PORT", "8092")))
    parser.add_argument("--on-demand-only", action="store_true",
                        help="process only jobs explicitly requested by a user")
    args = parser.parse_args()
    config = load_config(args.config)
    stop = False
    def handle_signal(_signum, _frame):
        nonlocal stop
        stop = True
    signal.signal(signal.SIGTERM, handle_signal)
    signal.signal(signal.SIGINT, handle_signal)
    worker = Worker(config, args.queue)
    # Publish the configured region envelopes on startup. This makes the
    # on-demand catalog available without starting the scheduler or touching
    # legacy queued jobs from a previous staging run.
    worker._write_catalog()
    class ControlHandler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *values):
            return

        def reply(self, code: int, value: dict):
            body = json.dumps(value, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def region_id(self):
            match = re.fullmatch(r"/internal/builds/([a-z0-9][a-z0-9._-]{0,63})", urlsplit(self.path).path)
            return match.group(1) if match and not urlsplit(self.path).query else None

        def do_POST(self):
            region_id = self.region_id()
            if region_id is None or self.headers.get("Content-Length", "0") != "0":
                return self.reply(400, {"errorCode": "invalid_request"})
            value = worker.request_build(region_id)
            self.reply(202 if value["status"] in {"queued", "building"} else 200 if value["status"] == "ready" else 404,
                       value)

        def do_GET(self):
            region_id = self.region_id()
            if region_id is None:
                return self.reply(400, {"errorCode": "invalid_request"})
            value = worker.build_status(region_id)
            self.reply(200, value)

    control_server = ThreadingHTTPServer((args.control_host, args.control_port), ControlHandler)
    control_thread = threading.Thread(target=control_server.serve_forever, name="VoltyOfflineControl", daemon=True)
    control_thread.start()
    while not stop:
        worker.run_once(on_demand_only=args.on_demand_only)
        time.sleep(config.poll_seconds)
    control_server.shutdown()
    control_server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
