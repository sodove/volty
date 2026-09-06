"""Durable, idempotent scheduler for the production bundle."""

from __future__ import annotations

import hashlib
import json
import os
import argparse
import signal
import time
import tempfile
from pathlib import Path

from .config import ProductionConfig


def _fingerprint(region: object) -> str:
    payload = json.dumps(region, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    return hashlib.sha256(payload).hexdigest()


class Scheduler:
    def __init__(self, config: ProductionConfig, queue_path: Path):
        self.config = config
        self.queue_path = queue_path.resolve()

    def _read(self) -> dict[str, object]:
        if not self.queue_path.exists():
            return {"jobs": []}
        value = json.loads(self.queue_path.read_text(encoding="utf-8"))
        if not isinstance(value, dict) or not isinstance(value.get("jobs", []), list):
            raise ValueError("queue file is malformed")
        return value

    def _write(self, value: dict[str, object]) -> None:
        self.queue_path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=self.queue_path.parent,
                                         prefix=f".{self.queue_path.name}.", delete=False) as handle:
            json.dump(value, handle, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            handle.flush()
            os.fsync(handle.fileno())
            temporary = Path(handle.name)
        temporary.replace(self.queue_path)

    def tick(self, now: int) -> list[str]:
        lock_path = self.queue_path.with_suffix(self.queue_path.suffix + ".lock")
        try:
            lock = lock_path.open("x", encoding="ascii")
        except FileExistsError:
            return []
        try:
            lock.close()
            return self._tick_locked(now)
        finally:
            lock_path.unlink(missing_ok=True)

    def _tick_locked(self, now: int) -> list[str]:
        period = now // 86_400
        state = self._read()
        jobs = state["jobs"]
        existing = {(job.get("regionId"), job.get("period"), job.get("fingerprint"))
                    for job in jobs if isinstance(job, dict)}
        issued: list[str] = []
        for region in self.config.regions:
            fingerprint = _fingerprint({"id": region.id, "sourceUrl": region.source_url, "bbox": region.bbox})
            key = (region.id, period, fingerprint)
            if key in existing:
                continue
            job_id = f"{region.id}-{period}-{fingerprint[:12]}"
            jobs.append({"id": job_id, "regionId": region.id, "sourceId": region.source_id or region.id,
                         "sourceUrl": region.source_url, "bbox": region.bbox, "period": period,
                         "fingerprint": fingerprint,
                         "state": "queued"})
            existing.add(key)
            issued.append(job_id)
        if issued:
            self._write(state)
        return issued


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--queue", type=Path, required=True)
    args = parser.parse_args()
    from .config import load_config
    config = load_config(args.config)
    scheduler = Scheduler(config, args.queue)
    stop = False
    def handle_signal(_signum, _frame):
        nonlocal stop
        stop = True
    signal.signal(signal.SIGTERM, handle_signal)
    signal.signal(signal.SIGINT, handle_signal)
    while not stop:
        scheduler.tick(int(time.time()))
        time.sleep(config.poll_seconds)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
