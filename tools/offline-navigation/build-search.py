#!/usr/bin/env python3
"""Build the small FTS4 place index shipped in a Volty region package.

The input is GeoJSON exported from the region PBF after an OSM name/tag filter.
The database intentionally stores only searchable features and their point
location; the map and routing components remain independent and replaceable.
"""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
from math import cos, radians, sqrt
from pathlib import Path
from typing import Any, Iterable


def _values(properties: dict[str, Any], *keys: str) -> list[str]:
    values: list[str] = []
    for key in keys:
        value = properties.get(key)
        if isinstance(value, str) and value.strip():
            values.append(value.strip())
    return values


def _search_normalize(value: str) -> str:
    """Keep display text intact while matching Kotlin's Russian token folding."""
    return value.lower().replace("ё", "е")


def _centroid(coordinates: Any) -> tuple[float, float] | None:
    points: list[tuple[float, float]] = []

    def visit(value: Any) -> None:
        if isinstance(value, (list, tuple)) and len(value) >= 2:
            if isinstance(value[0], (int, float)) and isinstance(value[1], (int, float)):
                points.append((float(value[0]), float(value[1])))
                return
            for child in value:
                visit(child)

    visit(coordinates)
    if not points:
        return None
    lon = sum(point[0] for point in points) / len(points)
    lat = sum(point[1] for point in points) / len(points)
    return lat, lon


def _kind(properties: dict[str, Any]) -> str:
    for key in ("place", "amenity", "shop", "tourism", "highway", "railway"):
        value = properties.get(key)
        if isinstance(value, str) and value:
            return f"{key}:{value}"
    return "feature"


Row = tuple[str, str, float, float, str, str]


def _name_normalize(value: str) -> str:
    value = re.sub(r"[^\w\s]+", " ", value.lower().replace("ё", "е"), flags=re.UNICODE)
    return " ".join(value.split())


def _distance_meters(left: Row, right: Row) -> float:
    latitude = radians((left[2] + right[2]) / 2.0)
    north = radians(right[2] - left[2]) * 6_371_000.0
    east = radians(right[3] - left[3]) * 6_371_000.0 * cos(latitude)
    return sqrt(north * north + east * east)


def _row_quality(row: Row) -> tuple[int, int, int]:
    semantic = {"shop:mall": 30, "amenity:food_court": 20}.get(row[4], 0)
    return semantic, len(row[1]), len(row[0])


def _deduplicate_rows(rows: Iterable[Row]) -> list[Row]:
    result: list[Row] = []
    identity_index: dict[str, int] = {}
    spatial_index: dict[tuple[str, int, int], list[int]] = {}

    def cell(row: Row) -> tuple[int, int]:
        latitude = radians(row[2])
        return (
            int((radians(row[3]) * 6_371_000.0 * cos(latitude)) // 50.0),
            int((radians(row[2]) * 6_371_000.0) // 50.0),
        )

    for row in rows:
        name = _name_normalize(row[0])
        x, y = cell(row)
        candidate_indices: set[int] = set()
        if row[5] in identity_index:
            candidate_indices.add(identity_index[row[5]])
        if name:
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    candidate_indices.update(spatial_index.get((name, x + dx, y + dy), ()))
        duplicate_index = next((index for index in candidate_indices
                                if (row[5] and result[index][5] == row[5]) or
                                   (name and name == _name_normalize(result[index][0]) and
                                    _distance_meters(row, result[index]) <= 50.0)), None)
        if duplicate_index is None:
            duplicate_index = len(result)
            result.append(row)
        elif _row_quality(row) > _row_quality(result[duplicate_index]):
            result[duplicate_index] = row
        if row[5]:
            identity_index[row[5]] = duplicate_index
        if name:
            spatial_index.setdefault((name, x, y), []).append(duplicate_index)
    return result


def _rows(features: Iterable[dict[str, Any]]) -> Iterable[Row]:
    raw_rows: list[Row] = []
    for feature in features:
        properties = feature.get("properties")
        geometry = feature.get("geometry")
        if not isinstance(properties, dict) or not isinstance(geometry, dict):
            continue
        names = _values(properties, "name:ru", "name", "official_name", "alt_name")
        address = _values(
            properties,
            "addr:city",
            "addr:place",
            "addr:street",
            "addr:housenumber",
            "address",
        )
        if not names and not address:
            continue
        point = _centroid(geometry.get("coordinates"))
        if point is None:
            continue
        display_name = names[0] if names else " ".join(address)
        search_text = _search_normalize(" ".join(dict.fromkeys(names + address)))
        osm_id = str(properties.get("id", properties.get("osm_id", "")))
        raw_rows.append((display_name, search_text, point[0], point[1], _kind(properties), osm_id))
    yield from _deduplicate_rows(raw_rows)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("geojson", type=Path)
    parser.add_argument("database", type=Path)
    parser.add_argument("--region-id", default="ekb-agglomeration")
    args = parser.parse_args()

    if args.database.exists():
        args.database.unlink()
    args.database.parent.mkdir(parents=True, exist_ok=True)

    connection = sqlite3.connect(args.database)
    try:
        connection.execute("PRAGMA journal_mode=DELETE")
        connection.execute("PRAGMA synchronous=FULL")
        connection.execute(
            "CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)"
        )
        connection.execute(
            "CREATE VIRTUAL TABLE places USING fts4("
            "display_name, search_text, latitude, longitude, kind, osm_id, "
            "tokenize=unicode61)"
        )
        connection.executemany(
            "INSERT INTO metadata(key, value) VALUES (?, ?)",
            [("schema", "1"), ("region_id", args.region_id), ("source", "OpenStreetMap")],
        )

        with args.geojson.open("r", encoding="utf-8") as source:
            document = json.load(source)
        features = document.get("features", [])
        if not isinstance(features, list):
            raise ValueError("GeoJSON features must be an array")

        inserted = 0
        for row in _rows(features):
            connection.execute(
                "INSERT INTO places(display_name, search_text, latitude, longitude, kind, osm_id) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                row,
            )
            inserted += 1
        connection.execute("INSERT INTO metadata(key, value) VALUES (?, ?)", ("rows", str(inserted)))
        connection.commit()
        connection.execute("VACUUM")
        print(f"indexed {inserted} searchable features into {args.database}")
    finally:
        connection.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
