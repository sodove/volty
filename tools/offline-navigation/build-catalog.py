#!/usr/bin/env python3
"""Build the public regional catalog from signed release manifests."""

from __future__ import annotations

import argparse
import json
import math
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

EXPECTED_ROUTING_DATA_VERSION = "valhalla-3.6.3"


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path}: root must be an object")
    return value


def finite_bbox(value: object, name: str) -> list[float]:
    if not isinstance(value, list) or len(value) != 4:
        raise ValueError(f"{name}: expected [west, south, east, north]")
    bbox = [float(item) for item in value]
    west, south, east, north = bbox
    if any(not math.isfinite(item) for item in bbox):
        raise ValueError(f"{name}: coordinates must be finite")
    if not (-180 <= west <= east <= 180 and -90 <= south <= north <= 90):
        raise ValueError(f"{name}: invalid coordinate order or range")
    return bbox


def coverage_covers(coverage: list[float], bounds: list[float]) -> bool:
    return (
        coverage[0] <= bounds[0]
        and coverage[1] <= bounds[1]
        and coverage[2] >= bounds[2]
        and coverage[3] >= bounds[3]
    )


def signed_manifest(
    manifest_path: Path,
    expected_region_id: str,
    expected_routing_data_version: str,
) -> dict[str, Any]:
    manifest = load_json(manifest_path)
    if manifest.get("regionId") != expected_region_id:
        raise ValueError(
            f"{manifest_path}: regionId {manifest.get('regionId')!r} does not match "
            f"entry {expected_region_id!r}"
        )
    signature = manifest.get("manifestSignature")
    if not isinstance(signature, dict):
        raise ValueError(f"{manifest_path}: signed manifest is required")
    if str(signature.get("algorithm", "")).strip().lower() != "ed25519":
        raise ValueError(f"{manifest_path}: manifest must use Ed25519")
    if (
        not str(signature.get("keyId", "")).strip()
        or str(signature.get("keyId", "")).strip() == "UNSIGNED_DEV"
        or not str(signature.get("value", "")).strip()
        or str(signature.get("value", "")).strip() == "UNSIGNED"
    ):
        raise ValueError(f"{manifest_path}: unsigned manifest cannot enter catalog")
    coverage_object = manifest.get("coverage")
    if not isinstance(coverage_object, dict):
        raise ValueError(f"{manifest_path}: coverage must be an object")
    coverage = finite_bbox(coverage_object.get("bbox"), f"{manifest_path}: coverage.bbox")
    if not manifest.get("releaseVersion"):
        raise ValueError(f"{manifest_path}: releaseVersion is required")
    compatibility = manifest.get("compatibility")
    if not isinstance(compatibility, dict):
        raise ValueError(f"{manifest_path}: compatibility must be an object")
    routing_data_version = str(compatibility.get("routingDataVersion", "")).strip()
    if routing_data_version != expected_routing_data_version:
        raise ValueError(
            f"{manifest_path}: routingDataVersion {routing_data_version!r} does not match "
            f"{expected_routing_data_version!r}"
        )
    # Keep this check local to the publishing tool; cryptographic verification
    # still happens in the app with the public key configured in the APK.
    manifest["coverage"]["bbox"] = coverage
    return manifest


def build_catalog(
    spec_path: Path,
    generated_at: str | None,
    expected_routing_data_version: str = EXPECTED_ROUTING_DATA_VERSION,
) -> dict[str, Any]:
    spec = load_json(spec_path)
    entries = spec.get("regions")
    if not isinstance(entries, list) or not entries:
        raise ValueError(f"{spec_path}: regions must be a non-empty array")

    seen: set[str] = set()
    catalog_entries: list[dict[str, Any]] = []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            raise ValueError(f"{spec_path}: regions[{index}] must be an object")
        region_id = str(entry.get("regionId", "")).strip()
        display_name = str(entry.get("displayName", "")).strip()
        if not region_id or not display_name:
            raise ValueError(f"{spec_path}: regions[{index}] needs regionId and displayName")
        if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", region_id):
            raise ValueError(f"{spec_path}: invalid regionId {region_id!r}")
        if region_id in seen:
            raise ValueError(f"{spec_path}: duplicate regionId {region_id}")
        seen.add(region_id)

        manifest_value = entry.get("manifest")
        if not isinstance(manifest_value, str) or not manifest_value.strip():
            raise ValueError(f"{spec_path}: regions[{index}].manifest is required")
        manifest_path = Path(manifest_value)
        if not manifest_path.is_absolute():
            manifest_path = spec_path.parent / manifest_path
        manifest = signed_manifest(
            manifest_path,
            region_id,
            expected_routing_data_version,
        )
        coverage = finite_bbox(manifest["coverage"]["bbox"], f"{region_id}: coverage.bbox")
        bounds = finite_bbox(entry.get("bounds", coverage), f"{region_id}: bounds")
        if not coverage_covers(coverage, bounds):
            raise ValueError(f"{region_id}: logical bounds exceed signed release coverage")

        catalog_entries.append(
            {
                "region": {
                    "regionId": region_id,
                    "displayName": display_name,
                    "bounds": {
                        "south": bounds[1],
                        "west": bounds[0],
                        "north": bounds[3],
                        "east": bounds[2],
                    },
                },
                "latestRelease": manifest,
            }
        )

    return {
        "schemaVersion": 1,
        "generatedAt": generated_at or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "regions": catalog_entries,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--spec", type=Path, required=True, help="JSON file describing catalog regions")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--generated-at", help="optional reproducible ISO-8601 catalog timestamp")
    parser.add_argument(
        "--routing-data-version",
        default=EXPECTED_ROUTING_DATA_VERSION,
        help="routing data version consumed by the bundled Valhalla Mobile engine",
    )
    args = parser.parse_args()

    catalog = build_catalog(
        args.spec,
        args.generated_at,
        expected_routing_data_version=args.routing_data_version,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(f".{args.output.name}.tmp")
    temporary.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(args.output)
    print(f"wrote catalog with {len(catalog['regions'])} regions to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
