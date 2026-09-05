"""Small immutable wire models shared by the worker and publisher."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from typing import Any


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def content_hash(value: Any) -> str:
    return hashlib.sha256(canonical_json(value)).hexdigest()


@dataclass(frozen=True)
class SourceSnapshot:
    source_id: str
    url: str
    sha256: str
    size_bytes: int
    osm_timestamp: str
    replication_sequence: int | None
    geometry_hash: str
    fetched_at: str

    def to_json(self) -> dict[str, Any]:
        return {
            "sourceId": self.source_id, "url": self.url, "sha256": self.sha256,
            "sizeBytes": self.size_bytes, "osmTimestamp": self.osm_timestamp,
            "replicationSequence": self.replication_sequence,
            "geometryHash": self.geometry_hash, "fetchedAt": self.fetched_at,
        }


def now_utc() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
