#!/usr/bin/env python3
"""Write the unsigned release envelope for a regional package."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


def artifact(path: Path, installed_root: Path, **extra: object) -> dict[str, object]:
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    installed_bytes = sum(
        child.stat().st_size for child in installed_root.rglob("*") if child.is_file()
    )
    result: dict[str, object] = {
        "url": extra.pop("url"),
        "downloadBytes": path.stat().st_size,
        "installedBytes": installed_bytes,
        "sha256": digest,
    }
    result.update(extra)
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--routing", type=Path, required=True)
    parser.add_argument("--routing-installed", type=Path, required=True)
    parser.add_argument("--search", type=Path, required=True)
    parser.add_argument("--search-installed", type=Path, required=True)
    parser.add_argument("--map", type=Path, required=True)
    parser.add_argument("--map-installed", type=Path, required=True)
    parser.add_argument("--region-id", required=True)
    parser.add_argument("--release-version", required=True)
    parser.add_argument("--min-app-version-code", type=int, required=True)
    parser.add_argument("--osm-sequence", type=int, required=True)
    parser.add_argument("--osm-timestamp", required=True)
    parser.add_argument("--bbox", required=True, help="west,south,east,north")
    parser.add_argument("--routing-buffer-km", type=int, default=20)
    parser.add_argument("--base-url", default="https://cdn.example.invalid/volty/regions")
    args = parser.parse_args()

    bbox = [float(value) for value in args.bbox.split(",")]
    if len(bbox) != 4:
        raise ValueError("bbox must contain four comma-separated numbers")

    created_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    prefix = f"{args.base_url.rstrip('/')}/{args.region_id}/{args.release_version}"
    manifest = {
        "schemaVersion": 2,
        "regionId": args.region_id,
        "releaseVersion": args.release_version,
        "createdAt": created_at,
        "source": {
            "osmReplicationSequence": args.osm_sequence,
            "osmTimestamp": args.osm_timestamp,
        },
        "compatibility": {
            "minAppVersionCode": args.min_app_version_code,
            "routingEngine": "valhalla",
            "routingDataVersion": "valhalla-3.8.3",
            "mapSchemaVersion": 1,
            "searchSchemaVersion": 1,
        },
        "coverage": {
            "bbox": bbox,
            "routingBufferKm": args.routing_buffer_km,
        },
        "components": {
            "routing": artifact(
                args.routing,
                args.routing_installed,
                url=f"{prefix}/routing/valhalla-routing.tar.gz",
                compression="gzip",
            ),
            "search": artifact(
                args.search,
                args.search_installed,
                url=f"{prefix}/search/places.sqlite.gz",
                schemaVersion=1,
                compression="gzip",
            ),
            "map": artifact(
                args.map,
                args.map_installed,
                url=f"{prefix}/map/ekb.pmtiles",
                format="pmtiles",
                minZoom=5,
                maxZoom=14,
                vectorLayerSchema=1,
            ),
        },
        "manifestSignature": {
            "keyId": "UNSIGNED_DEV",
            "algorithm": "ed25519",
            "value": "UNSIGNED",
        },
    }
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote unsigned manifest to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
