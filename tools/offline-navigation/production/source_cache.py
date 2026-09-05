"""Safe public-source selection and immutable resumable downloads."""

from __future__ import annotations

import hashlib
import ipaddress
import json
import os
import socket
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen

from .models import SourceSnapshot, now_utc


class SourceError(ValueError):
    pass


def validate_public_url(value: str) -> str:
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise SourceError("source URL must be credential-free HTTPS")
    try:
        addresses = {item[4][0] for item in socket.getaddrinfo(parsed.hostname, 443, type=socket.SOCK_STREAM)}
    except socket.gaierror as error:
        raise SourceError("source host cannot be resolved") from error
    for address in addresses:
        ip = ipaddress.ip_address(address)
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_unspecified or ip.is_reserved:
            raise SourceError("source URL resolves to a private or reserved address")
    return value


def public_pbf_url(index: dict, source_id: str) -> str:
    features = index.get("features")
    if not isinstance(features, list):
        raise SourceError("source index features are missing")
    for feature in features:
        props = feature.get("properties") if isinstance(feature, dict) else None
        if isinstance(props, dict) and props.get("id") == source_id:
            urls = props.get("urls")
            value = urls.get("pbf") if isinstance(urls, dict) else None
            if not isinstance(value, str):
                raise SourceError("public PBF URL is missing")
            return validate_public_url(value)
    raise SourceError(f"source is not in the public index: {source_id}")


def fetch_snapshot(source_id: str, url: str, cache_root: Path, *, max_bytes: int,
                   osm_timestamp: str, replication_sequence: int | None,
                   geometry_hash: str) -> SourceSnapshot:
    validate_public_url(url)
    if not osm_timestamp or not geometry_hash:
        raise SourceError("source_metadata_required")
    cache_root = cache_root.resolve()
    cache_root.mkdir(parents=True, exist_ok=True)
    final = cache_root / f"{source_id}.osm.pbf"
    metadata = cache_root / f"{source_id}.json"
    if final.is_file() and metadata.is_file():
        snapshot = SourceSnapshot(**_from_json(metadata))
        if snapshot.url != url or snapshot.osm_timestamp != osm_timestamp or snapshot.geometry_hash != geometry_hash:
            raise SourceError("immutable_source_metadata_conflict")
        return snapshot

    partial = final.with_suffix(final.suffix + ".partial")
    offset = partial.stat().st_size if partial.exists() else 0
    headers = {"User-Agent": "Volty-offline-worker/1", "Accept-Encoding": "identity"}
    if offset:
        headers["Range"] = f"bytes={offset}-"
    request = Request(url, headers=headers)
    try:
        with urlopen(request, timeout=30) as response, partial.open("ab") as output:
            status = getattr(response, "status", 200)
            if offset and status != 206:
                output.close()
                partial.unlink(missing_ok=True)
                return fetch_snapshot(source_id, url, cache_root, max_bytes=max_bytes,
                                      osm_timestamp=osm_timestamp,
                                      replication_sequence=replication_sequence,
                                      geometry_hash=geometry_hash)
            total = offset
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > max_bytes:
                    raise SourceError("source download exceeds configured limit")
                output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
    except OSError as error:
        raise SourceError("source download failed; partial file is retained for retry") from error

    digest = hashlib.sha256()
    with partial.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    partial.replace(final)
    snapshot = SourceSnapshot(source_id, url, digest.hexdigest(), final.stat().st_size,
                              osm_timestamp, replication_sequence, geometry_hash, now_utc())
    metadata.write_text(json.dumps(snapshot.to_json(), sort_keys=True) + "\n", encoding="utf-8")
    return snapshot


def _from_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    return {
        "source_id": value["sourceId"], "url": value["url"], "sha256": value["sha256"],
        "size_bytes": value["sizeBytes"], "osm_timestamp": value["osmTimestamp"],
        "replication_sequence": value.get("replicationSequence"),
        "geometry_hash": value["geometryHash"], "fetched_at": value["fetchedAt"],
    }
