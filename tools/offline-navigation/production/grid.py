"""Canonical grid-v1 cells: one-degree, half-open, antimeridian-safe."""

from __future__ import annotations

import math
import re


class UnsupportedCoordinate(ValueError):
    pass


_BASE = re.compile(r"g1-(\d{3})-(\d{3})\Z")
_CELL = re.compile(r"g1-(\d{3})-(\d{3})(?:(-q[0-3])*)\Z")


def cell_id(lat: float, lon: float) -> str:
    if not math.isfinite(lat) or not math.isfinite(lon) or lat < -90.0 or lat >= 90.0:
        raise UnsupportedCoordinate("latitude is outside the supported half-open world")
    if lon < -180.0 or lon > 180.0:
        raise UnsupportedCoordinate("longitude is outside the supported world")
    normalized_lon = ((lon + 180.0) % 360.0) - 180.0
    return f"g1-{math.floor(lat + 90.0):03d}-{math.floor(normalized_lon + 180.0):03d}"


def _parts(region_id: str) -> tuple[int, int, str]:
    match = _CELL.fullmatch(region_id)
    if not match:
        raise ValueError("invalid grid-v1 cell")
    latitude, longitude, suffix = int(match[1]), int(match[2]), match[3] or ""
    if latitude >= 180 or longitude >= 360:
        raise ValueError("invalid grid-v1 cell index")
    return latitude, longitude, suffix


def cell_bounds(region_id: str) -> tuple[float, float, float, float]:
    latitude, longitude, suffix = _parts(region_id)
    south, west = -90.0 + latitude, -180.0 + longitude
    size = 1.0
    for item in re.findall(r"-q([0-3])", suffix):
        half = size / 2.0
        quadrant = int(item)
        if quadrant in (1, 3):
            west += half
        if quadrant in (2, 3):
            south += half
        size = half
    return west, south, west + size, south + size


def split_cell(region_id: str) -> list[str]:
    _latitude, _longitude, suffix = _parts(region_id)
    if len(re.findall(r"-q[0-3]", suffix)) >= 4:
        raise ValueError("grid-v1 maximum quadtree depth is four")
    return [region_id + f"-q{index}" for index in range(4)]
