#!/usr/bin/env python3
"""Build the public regional catalog from signed release manifests."""

from __future__ import annotations

import argparse
import base64
import copy
import json
import math
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

EXPECTED_ROUTING_DATA_VERSION = "valhalla-3.6.3"
EXPECTED_SCHEMA_VERSION = 2
EXPECTED_ROUTING_ENGINE = "valhalla"
EXPECTED_MAP_FORMAT = "pmtiles"
SHA256_PATTERN = re.compile(r"[0-9a-fA-F]{64}")
REGION_ID_PATTERN = re.compile(r"[a-z0-9][a-z0-9._-]{0,63}")
RELEASE_VERSION_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")


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


def canonical_payload(manifest: dict[str, Any]) -> bytes:
    """Match OfflineRegionPackageManifestCodec.signingPayload byte-for-byte."""

    unsigned = copy.deepcopy(manifest)
    unsigned.pop("manifestSignature", None)
    coverage = unsigned.get("coverage")
    if isinstance(coverage, dict) and coverage.get("polygonUrl") is None:
        coverage.pop("polygonUrl", None)
    components = unsigned.get("components")
    if isinstance(components, dict):
        for component_name in ("search", "map"):
            component = components.get(component_name)
            if isinstance(component, dict) and component.get("compression") is None:
                component.pop("compression", None)
    return json.dumps(
        unsigned,
        ensure_ascii=False,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def decode_signature(value: object, manifest_path: Path) -> bytes:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{manifest_path}: signature value is required")
    try:
        signature = base64.b64decode(value, validate=True)
    except Exception as error:
        raise ValueError(f"{manifest_path}: signature is not valid Base64") from error
    if len(signature) != 64:
        raise ValueError(f"{manifest_path}: Ed25519 signature must be 64 bytes")
    return signature


def validate_timestamp(value: object, name: str) -> None:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name}: timestamp is required")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{name}: invalid timestamp") from error
    if parsed.tzinfo is None:
        raise ValueError(f"{name}: timestamp must include a timezone")


def positive_int(value: object, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError(f"{name}: expected a positive integer")
    return value


def non_negative_int(value: object, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{name}: expected a non-negative integer")
    return value


def validate_artifact(component: object, name: str) -> None:
    if not isinstance(component, dict):
        raise ValueError(f"{name}: artifact must be an object")
    url = component.get("url")
    parsed_url = urlparse(url) if isinstance(url, str) else None
    if parsed_url is None or parsed_url.scheme != "https" or not parsed_url.netloc:
        raise ValueError(f"{name}.url: HTTPS URL is required")
    positive_int(component.get("downloadBytes"), f"{name}.downloadBytes")
    positive_int(component.get("installedBytes"), f"{name}.installedBytes")
    checksum = component.get("sha256")
    if not isinstance(checksum, str) or not SHA256_PATTERN.fullmatch(checksum):
        raise ValueError(f"{name}.sha256: expected a SHA-256 hex digest")


def validate_manifest_compatibility(
    manifest: dict[str, Any],
    manifest_path: Path,
    expected_routing_data_version: str,
    current_app_version_code: int | None,
) -> list[float]:
    if manifest.get("schemaVersion") != EXPECTED_SCHEMA_VERSION:
        raise ValueError(f"{manifest_path}: unsupported schemaVersion")
    region_id = manifest.get("regionId")
    if not isinstance(region_id, str) or not REGION_ID_PATTERN.fullmatch(region_id):
        raise ValueError(f"{manifest_path}: invalid regionId")
    release_version = manifest.get("releaseVersion")
    if not isinstance(release_version, str) or not RELEASE_VERSION_PATTERN.fullmatch(release_version):
        raise ValueError(f"{manifest_path}: invalid releaseVersion")
    validate_timestamp(manifest.get("createdAt"), f"{manifest_path}: createdAt")

    source = manifest.get("source")
    if not isinstance(source, dict):
        raise ValueError(f"{manifest_path}: source must be an object")
    non_negative_int(source.get("osmReplicationSequence"), f"{manifest_path}: source.osmReplicationSequence")
    validate_timestamp(source.get("osmTimestamp"), f"{manifest_path}: source.osmTimestamp")

    compatibility = manifest.get("compatibility")
    if not isinstance(compatibility, dict):
        raise ValueError(f"{manifest_path}: compatibility must be an object")
    min_app_version_code = non_negative_int(
        compatibility.get("minAppVersionCode"),
        f"{manifest_path}: compatibility.minAppVersionCode",
    )
    if current_app_version_code is not None and min_app_version_code > current_app_version_code:
        raise ValueError(f"{manifest_path}: manifest requires a newer app version")
    if str(compatibility.get("routingEngine", "")).lower() != EXPECTED_ROUTING_ENGINE:
        raise ValueError(f"{manifest_path}: unsupported routingEngine")
    if compatibility.get("routingDataVersion") != expected_routing_data_version:
        raise ValueError(f"{manifest_path}: routingDataVersion does not match the mobile engine")
    if non_negative_int(compatibility.get("mapSchemaVersion"), f"{manifest_path}: mapSchemaVersion") < 1:
        raise ValueError(f"{manifest_path}: mapSchemaVersion is unsupported")
    if non_negative_int(compatibility.get("searchSchemaVersion"), f"{manifest_path}: searchSchemaVersion") < 1:
        raise ValueError(f"{manifest_path}: searchSchemaVersion is unsupported")

    coverage_object = manifest.get("coverage")
    if not isinstance(coverage_object, dict):
        raise ValueError(f"{manifest_path}: coverage must be an object")
    routing_buffer = coverage_object.get("routingBufferKm")
    if isinstance(routing_buffer, bool) or not isinstance(routing_buffer, int) or not 0 <= routing_buffer <= 100:
        raise ValueError(f"{manifest_path}: coverage.routingBufferKm is invalid")
    coverage = finite_bbox(coverage_object.get("bbox"), f"{manifest_path}: coverage.bbox")

    components = manifest.get("components")
    if not isinstance(components, dict):
        raise ValueError(f"{manifest_path}: components must be an object")
    routing = components.get("routing")
    search = components.get("search")
    map_component = components.get("map")
    validate_artifact(routing, f"{manifest_path}: components.routing")
    validate_artifact(search, f"{manifest_path}: components.search")
    validate_artifact(map_component, f"{manifest_path}: components.map")
    if not isinstance(routing.get("compression"), str) or not routing["compression"].strip():
        raise ValueError(f"{manifest_path}: routing compression is required")
    if non_negative_int(search.get("schemaVersion"), f"{manifest_path}: search.schemaVersion") < 1:
        raise ValueError(f"{manifest_path}: search schema is unsupported")
    if str(map_component.get("format", "")).lower() != EXPECTED_MAP_FORMAT:
        raise ValueError(f"{manifest_path}: map format is unsupported")
    min_zoom = non_negative_int(map_component.get("minZoom"), f"{manifest_path}: map.minZoom")
    max_zoom = non_negative_int(map_component.get("maxZoom"), f"{manifest_path}: map.maxZoom")
    if max_zoom > 24 or min_zoom > max_zoom:
        raise ValueError(f"{manifest_path}: map zoom range is invalid")
    if non_negative_int(map_component.get("vectorLayerSchema"), f"{manifest_path}: map.vectorLayerSchema") < 1:
        raise ValueError(f"{manifest_path}: vector layer schema is unsupported")
    return coverage


def signed_manifest(
    manifest_path: Path,
    expected_region_id: str,
    expected_routing_data_version: str,
    public_key: Ed25519PublicKey | None = None,
    expected_key_id: str | None = None,
    current_app_version_code: int | None = None,
) -> dict[str, Any]:
    manifest = load_json(manifest_path)
    if manifest.get("regionId") != expected_region_id:
        raise ValueError(
            f"{manifest_path}: regionId {manifest.get('regionId')!r} does not match "
            f"entry {expected_region_id!r}"
        )
    coverage = validate_manifest_compatibility(
        manifest,
        manifest_path,
        expected_routing_data_version,
        current_app_version_code,
    )
    signature = manifest.get("manifestSignature")
    if not isinstance(signature, dict):
        raise ValueError(f"{manifest_path}: signed manifest is required")
    key_id = str(signature.get("keyId", "")).strip()
    if str(signature.get("algorithm", "")).strip().lower() != "ed25519":
        raise ValueError(f"{manifest_path}: manifest must use Ed25519")
    if (
        not key_id
        or key_id == "UNSIGNED_DEV"
        or not str(signature.get("value", "")).strip()
        or str(signature.get("value", "")).strip() == "UNSIGNED"
    ):
        raise ValueError(f"{manifest_path}: unsigned manifest cannot enter catalog")
    if expected_key_id is not None and key_id != expected_key_id:
        raise ValueError(f"{manifest_path}: manifest uses an unexpected signing key")
    if public_key is not None:
        try:
            public_key.verify(
                decode_signature(signature.get("value"), manifest_path),
                canonical_payload(manifest),
            )
        except InvalidSignature as error:
            raise ValueError(f"{manifest_path}: manifest signature verification failed") from error
    manifest["coverage"]["bbox"] = coverage
    return manifest


def load_public_key(path: Path) -> Ed25519PublicKey:
    encoded = path.read_bytes().strip()
    try:
        raw = encoded if len(encoded) == 32 else base64.b64decode(encoded, validate=True)
    except Exception as error:
        raise ValueError(f"{path}: public key must be raw 32 bytes or Base64") from error
    if len(raw) != 32:
        raise ValueError(f"{path}: Ed25519 public key must be 32 bytes")
    return Ed25519PublicKey.from_public_bytes(raw)


def build_catalog(
    spec_path: Path,
    generated_at: str | None,
    expected_routing_data_version: str = EXPECTED_ROUTING_DATA_VERSION,
    public_key: Ed25519PublicKey | None = None,
    expected_key_id: str | None = None,
    current_app_version_code: int | None = None,
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
            public_key=public_key,
            expected_key_id=expected_key_id,
            current_app_version_code=current_app_version_code,
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
    parser.add_argument(
        "--public-key",
        type=Path,
        required=True,
        help="Ed25519 public key (raw 32 bytes or Base64) for publisher-side verification",
    )
    parser.add_argument("--key-id", required=True, help="expected production manifest key id")
    parser.add_argument(
        "--current-app-version-code",
        type=int,
        required=True,
        help="app version that must be able to consume every advertised release",
    )
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
        public_key=load_public_key(args.public_key),
        expected_key_id=args.key_id,
        current_app_version_code=args.current_app_version_code,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(f".{args.output.name}.tmp")
    temporary.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(args.output)
    print(f"wrote catalog with {len(catalog['regions'])} regions to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
