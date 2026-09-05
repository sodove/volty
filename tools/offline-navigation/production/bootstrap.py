"""Build a deterministic region inventory from a public GeoJSON extract index."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import time
from pathlib import Path
from urllib.request import Request, urlopen


DEFAULT_INDEX_URL = "https://download.geofabrik.de/index-v1.json"


class BootstrapError(ValueError):
    """The source index or geometry cannot produce a safe inventory."""


def _load_json(source: str) -> tuple[dict, str]:
    if source.startswith("https://"):
        request = Request(source, headers={"User-Agent": "volty-offline-bootstrap/1"})
        with urlopen(request, timeout=30) as response:
            return json.loads(response.read()), source
    path = Path(source).resolve()
    return json.loads(path.read_text(encoding="utf-8")), str(path)


def _features(document: dict) -> list[dict]:
    if document.get("type") != "FeatureCollection" or not isinstance(document.get("features"), list):
        raise BootstrapError("source index must be a GeoJSON FeatureCollection")
    result = []
    for feature in document["features"]:
        if isinstance(feature, dict) and feature.get("type") == "Feature" and isinstance(feature.get("geometry"), dict):
            result.append(feature)
    if not result:
        raise BootstrapError("source index contains no usable features")
    return result


def _feature_id(feature: dict) -> str:
    properties = feature.get("properties") or {}
    for key in ("id", "path", "name"):
        value = properties.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip().rstrip("/").split("/")[-1]
    raise BootstrapError("source feature has no stable id")


def _source_url(feature: dict) -> str | None:
    properties = feature.get("properties") or {}
    urls = properties.get("urls") or {}
    value = urls.get("pbf") if isinstance(urls, dict) else None
    if isinstance(value, str) and value.startswith("https://") and "@" not in value:
        return value
    return None


def _coordinates(geometry: dict) -> list[list[float]]:
    kind = geometry.get("type")
    coordinates = geometry.get("coordinates")
    result: list[list[float]] = []

    def visit(value: object) -> None:
        if isinstance(value, list) and len(value) >= 2 and all(isinstance(item, (int, float)) for item in value[:2]):
            result.append([float(value[0]), float(value[1])])
        elif isinstance(value, list):
            for item in value:
                visit(item)

    if kind not in {"Point", "MultiPoint", "LineString", "MultiLineString", "Polygon", "MultiPolygon"}:
        raise BootstrapError(f"unsupported geometry type: {kind}")
    visit(coordinates)
    if not result:
        raise BootstrapError("geometry has no coordinates")
    return result


def geometry_bounds(geometry: dict) -> tuple[float, float, float, float]:
    points = _coordinates(geometry)
    return min(p[0] for p in points), min(p[1] for p in points), max(p[0] for p in points), max(p[1] for p in points)


def _point_on_segment(point: tuple[float, float], first: list[float], second: list[float]) -> bool:
    px, py = point
    ax, ay = first
    bx, by = second
    cross = (px - ax) * (by - ay) - (py - ay) * (bx - ax)
    if abs(cross) > 1e-12:
        return False
    return min(ax, bx) - 1e-12 <= px <= max(ax, bx) + 1e-12 and min(ay, by) - 1e-12 <= py <= max(ay, by) + 1e-12


def _point_in_ring(point: tuple[float, float], ring: list[list[float]]) -> bool:
    inside = False
    for index, first in enumerate(ring):
        second = ring[(index + 1) % len(ring)]
        if _point_on_segment(point, first, second):
            return True
        x1, y1 = first
        x2, y2 = second
        if (y1 > point[1]) != (y2 > point[1]):
            x = (x2 - x1) * (point[1] - y1) / (y2 - y1) + x1
            if point[0] < x:
                inside = not inside
    return inside


def _point_in_polygon(point: tuple[float, float], polygon: list[list[list[float]]]) -> bool:
    return _point_in_ring(point, polygon[0]) and not any(_point_in_ring(point, ring) for ring in polygon[1:])


def _orientation(first: list[float], second: list[float], third: list[float]) -> float:
    return (second[0] - first[0]) * (third[1] - first[1]) - (second[1] - first[1]) * (third[0] - first[0])


def _segments_intersect(first: list[float], second: list[float], third: list[float], fourth: list[float]) -> bool:
    values = (_orientation(first, second, third), _orientation(first, second, fourth),
              _orientation(third, fourth, first), _orientation(third, fourth, second))
    if values[0] == 0 and _point_on_segment((third[0], third[1]), first, second):
        return True
    if values[1] == 0 and _point_on_segment((fourth[0], fourth[1]), first, second):
        return True
    if values[2] == 0 and _point_on_segment((first[0], first[1]), third, fourth):
        return True
    if values[3] == 0 and _point_on_segment((second[0], second[1]), third, fourth):
        return True
    return (values[0] > 0) != (values[1] > 0) and (values[2] > 0) != (values[3] > 0)


def _ring_intersects_bbox(ring: list[list[float]], bbox: tuple[float, float, float, float]) -> bool:
    west, south, east, north = bbox
    rectangle = [([west, south], [east, south]), ([east, south], [east, north]),
                 ([east, north], [west, north]), ([west, north], [west, south])]
    for index, first in enumerate(ring):
        second = ring[(index + 1) % len(ring)]
        if any(_segments_intersect(first, second, edge[0], edge[1]) for edge in rectangle):
            return True
    return False


def _polygon_intersects_bbox(polygon: list[list[list[float]]], bbox: tuple[float, float, float, float]) -> bool:
    west, south, east, north = bbox
    outer_bounds = geometry_bounds({"type": "Polygon", "coordinates": polygon})
    if not _bbox_intersects(outer_bounds, bbox):
        return False
    corners = [(west, south), (east, south), (east, north), (west, north)]
    if any(_point_in_polygon(corner, polygon) for corner in corners):
        return True
    if any(west <= point[0] <= east and south <= point[1] <= north for point in polygon[0]):
        return True
    return any(_ring_intersects_bbox(ring, bbox) for ring in polygon)


def _bbox_intersects(first: tuple[float, float, float, float], second: tuple[float, float, float, float]) -> bool:
    return (max(first[0], second[0]) <= min(first[2], second[2]) and
            max(first[1], second[1]) <= min(first[3], second[3]))


def geometry_intersects_bbox(geometry: dict, bbox: tuple[float, float, float, float]) -> bool:
    kind = geometry.get("type")
    coordinates = geometry.get("coordinates")
    if not _bbox_intersects(geometry_bounds(geometry), bbox):
        return False
    if kind == "Polygon":
        return _polygon_intersects_bbox(coordinates, bbox)
    if kind == "MultiPolygon":
        return any(_polygon_intersects_bbox(polygon, bbox) for polygon in coordinates
                   if _bbox_intersects(geometry_bounds({"type": "Polygon", "coordinates": polygon}), bbox))
    west, south, east, north = bbox
    points = _coordinates(geometry)
    return any(west <= point[0] <= east and south <= point[1] <= north for point in points)


def _grid_cell_bounds(latitude: int, longitude: int) -> tuple[float, float, float, float]:
    return -180.0 + longitude, -90.0 + latitude, -179.0 + longitude, -89.0 + latitude


def _grid_id(latitude: int, longitude: int) -> str:
    return f"g1-{latitude:03d}-{longitude:03d}"


def _positive_bbox_overlap(first: tuple[float, float, float, float], second: tuple[float, float, float, float]) -> bool:
    return max(first[0], second[0]) < min(first[2], second[2]) and max(first[1], second[1]) < min(first[3], second[3])


def _mask_geometry(document: dict, source_id: str) -> dict:
    matches = [feature for feature in _features(document) if _feature_id(feature) == source_id]
    if not matches:
        raise BootstrapError(f"mask/source feature not found: {source_id}")
    return matches[0]["geometry"]


def _source_features(document: dict, source_id: str) -> list[dict]:
    """Return the smallest public extracts that can cover the requested mask.

    Geofabrik's index contains both a parent extract (``russia``) and its
    children (federal districts).  Selecting every PBF makes each cell appear
    to have many sources and turns a country bootstrap into an O(n*m) scan.
    Prefer the requested extract itself; only fall back to its direct children
    when the requested feature has no public PBF.
    """
    features = _features(document)
    exact: list[dict] = []
    children: list[dict] = []
    seen: set[str] = set()
    for feature in features:
        identifier = _feature_id(feature)
        url = _source_url(feature)
        if url is None or identifier in seen:
            continue
        seen.add(identifier)
        properties = feature.get("properties") or {}
        if identifier == source_id:
            exact.append(feature)
        elif properties.get("parent") == source_id:
            children.append(feature)
    return exact[:1] or children


def plan_inventory(index_document: dict, source_id: str, mask_document: dict | None = None,
                   *, index_source: str | None = None, mask_source: str | None = None) -> dict:
    features = _features(index_document)
    mask = _mask_geometry(mask_document or index_document, source_id)
    west, south, east, north = geometry_bounds(mask)
    first_lat = max(0, int(math.floor(south + 90.0)))
    last_lat = min(180, int(math.ceil(north + 90.0)))
    first_lon = max(0, int(math.floor(west + 180.0)))
    last_lon = min(360, int(math.ceil(east + 180.0)))
    candidates = [(feature, geometry_bounds(feature["geometry"]))
                  for feature in _source_features(index_document, source_id)]
    regions = []
    for latitude in range(first_lat, last_lat):
        for longitude in range(first_lon, last_lon):
            bbox = _grid_cell_bounds(latitude, longitude)
            if not geometry_intersects_bbox(mask, bbox):
                continue
            sources = [(feature, bounds) for feature, bounds in candidates
                       if _positive_bbox_overlap(bounds, bbox)
                       and geometry_intersects_bbox(feature["geometry"], bbox)]
            source_items = []
            for feature, bounds in sources:
                source_items.append({"id": _feature_id(feature), "url": _source_url(feature),
                                     "bounds": list(bounds)})
            source_items.sort(key=lambda item: (item["id"], item["url"] or ""))
            regions.append({"regionId": _grid_id(latitude, longitude), "logicalBbox": list(bbox),
                            "sourceIds": [item["id"] for item in source_items],
                            "sourceUrls": [item["url"] for item in source_items if item["url"]],
                            "sourceBounds": [item["bounds"] for item in source_items]})
    if not regions:
        raise BootstrapError("mask produced no grid regions")
    regions.sort(key=lambda item: item["regionId"])
    mask_hash = hashlib.sha256(json.dumps(mask, sort_keys=True, separators=(",", ":"),
                                          ensure_ascii=False).encode()).hexdigest()
    return {"schemaVersion": 1, "gridVersion": "g1", "sourceId": source_id,
            "sourceIndex": index_source, "maskSource": mask_source or index_source,
            "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "maskBounds": [west, south, east, north], "maskGeometrySha256": mask_hash,
            "regions": regions,
            "summary": {"plannedRegions": len(regions),
                         "regionsWithOneSource": sum(len(item["sourceIds"]) == 1 for item in regions),
                         "regionsWithMultipleSources": sum(len(item["sourceIds"]) > 1 for item in regions),
                         "regionsWithoutSource": sum(not item["sourceIds"] for item in regions)}}


def _fingerprint(region: dict) -> str:
    encoded = json.dumps(region, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    return hashlib.sha256(encoded).hexdigest()


def enqueue_inventory(inventory: dict, queue_path: Path) -> list[str]:
    regions = inventory.get("regions")
    if not isinstance(regions, list) or not regions:
        raise BootstrapError("inventory has no regions")
    state = json.loads(queue_path.read_text(encoding="utf-8")) if queue_path.exists() else {"jobs": []}
    jobs = state.get("jobs")
    if not isinstance(jobs, list):
        raise BootstrapError("queue file is malformed")
    existing = {(job.get("regionId"), job.get("fingerprint")) for job in jobs if isinstance(job, dict)}
    issued = []
    changed = False
    for region in regions:
        if len(region.get("sourceIds", [])) != 1 or len(region.get("sourceUrls", [])) != 1:
            raise BootstrapError(f"{region.get('regionId')}: exactly one covering PBF source is required")
        fingerprint = _fingerprint(region)
        key = (region["regionId"], fingerprint)
        if key in existing:
            for job in jobs:
                if not isinstance(job, dict) or (job.get("regionId"), job.get("fingerprint")) != key:
                    continue
                source_id = region["sourceIds"][0]
                if job.get("sourceId") is None:
                    job["sourceId"] = source_id
                    changed = True
                elif job.get("sourceId") != source_id:
                    raise BootstrapError(f"{region['regionId']}: existing job sourceId conflicts with inventory")
            continue
        job_id = f"{region['regionId']}-{fingerprint[:12]}"
        jobs.append({"id": job_id, "regionId": region["regionId"], "sourceId": region["sourceIds"][0],
                     "sourceUrl": region["sourceUrls"][0],
                     "bbox": ",".join(str(value) for value in region["logicalBbox"]),
                     "fingerprint": fingerprint, "state": "queued"})
        existing.add(key)
        issued.append(job_id)
    if issued or changed:
        queue_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = queue_path.with_suffix(queue_path.suffix + ".tmp")
        temporary.write_text(json.dumps(state, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
        temporary.replace(queue_path)
    return issued


def production_config_from_inventory(inventory: dict) -> dict:
    regions = inventory.get("regions")
    if not isinstance(regions, list) or not regions:
        raise BootstrapError("inventory has no regions")
    invalid = [item["regionId"] for item in regions
               if len(item.get("sourceIds", [])) != 1 or len(item.get("sourceUrls", [])) != 1]
    if invalid:
        raise BootstrapError("regions need one covering PBF source: " + ", ".join(invalid[:5]))
    return {"publicRoot": "/data/offline", "stagingRoot": "/data/staging", "sourceRoot": "/data/sources",
            "signingKey": "/run/secrets/volty-offline-signing-key.pem",
            "publicBaseUrl": "https://volty.sodove.ru/offline/regions",
            "keyId": "REPLACE_WITH_PROVISIONED_ED25519_KEY_ID", "minAppVersionCode": 31,
            "pollSeconds": 30, "maxDownloadBytes": 1 * 1024 * 1024 * 1024,
            "maxRuntimeSeconds": 86400, "buildScript": "/app/build-package.sh",
            "regions": [{"id": item["regionId"], "sourceId": item["sourceIds"][0],
                         "sourceUrl": item["sourceUrls"][0],
                         "bbox": ",".join(str(value) for value in item["logicalBbox"])}
                        for item in regions if len(item.get("sourceUrls", [])) == 1]}


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    plan = subparsers.add_parser("plan")
    plan.add_argument("--source-id", required=True)
    plan.add_argument("--index", default=DEFAULT_INDEX_URL)
    plan.add_argument("--mask")
    plan.add_argument("--output", type=Path, required=True)
    enqueue = subparsers.add_parser("enqueue")
    enqueue.add_argument("--inventory", type=Path, required=True)
    enqueue.add_argument("--queue", type=Path, required=True)
    enqueue.add_argument("--production-config", type=Path)
    status = subparsers.add_parser("status")
    status.add_argument("--inventory", type=Path, required=True)
    status.add_argument("--queue", type=Path)
    args = parser.parse_args()
    if args.command == "plan":
        index, index_source = _load_json(args.index)
        mask_source = None
        mask = None
        if args.mask:
            mask, mask_source = _load_json(args.mask)
        inventory = plan_inventory(index, args.source_id, mask,
                                   index_source=index_source, mask_source=mask_source)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(inventory, sort_keys=True, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"planned {inventory['summary']['plannedRegions']} regions to {args.output}")
        return 0
    inventory = json.loads(args.inventory.read_text(encoding="utf-8"))
    if args.command == "enqueue":
        issued = enqueue_inventory(inventory, args.queue)
        if args.production_config:
            args.production_config.parent.mkdir(parents=True, exist_ok=True)
            args.production_config.write_text(json.dumps(production_config_from_inventory(inventory),
                                                         sort_keys=True, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"enqueued {len(issued)} regions")
        return 0
    queue = json.loads(args.queue.read_text(encoding="utf-8")) if args.queue and args.queue.exists() else {"jobs": []}
    counts: dict[str, int] = {}
    for job in queue.get("jobs", []):
        counts[job.get("state", "unknown")] = counts.get(job.get("state", "unknown"), 0) + 1
    print(json.dumps({"regions": len(inventory.get("regions", [])), "jobs": counts}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
