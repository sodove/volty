#!/usr/bin/env python3
"""Expand a west,south,east,north bbox by a distance in kilometres."""

from __future__ import annotations

import argparse
import math


EARTH_KM_PER_DEGREE = 111.32


def parse_bbox(value: str) -> tuple[float, float, float, float]:
    try:
        bbox = tuple(float(part.strip()) for part in value.split(","))
    except ValueError as error:
        raise ValueError("bbox must contain four numeric comma-separated values") from error
    if len(bbox) != 4:
        raise ValueError("bbox must contain four numeric comma-separated values")
    west, south, east, north = bbox
    if not all(math.isfinite(item) for item in bbox):
        raise ValueError("bbox coordinates must be finite")
    if not (-180.0 <= west <= east <= 180.0 and -90.0 <= south <= north <= 90.0):
        raise ValueError("bbox has invalid coordinate order or range")
    return west, south, east, north


def expand_bbox(bbox: tuple[float, float, float, float], buffer_km: float) -> tuple[float, float, float, float]:
    if not math.isfinite(buffer_km) or buffer_km < 0.0:
        raise ValueError("routing buffer must be a finite non-negative number")
    west, south, east, north = bbox
    latitude_delta = buffer_km / EARTH_KM_PER_DEGREE
    # Use the smallest longitude scale at either latitude so the requested
    # distance is never under-approximated inside the bbox.
    cosine_scale = min(
        abs(math.cos(math.radians(south))),
        abs(math.cos(math.radians(north))),
    )
    longitude_delta = buffer_km / (EARTH_KM_PER_DEGREE * max(cosine_scale, 1e-6))
    return (
        max(-180.0, west - longitude_delta),
        max(-90.0, south - latitude_delta),
        min(180.0, east + longitude_delta),
        min(90.0, north + latitude_delta),
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("bbox", help="logical west,south,east,north bbox")
    parser.add_argument("buffer_km", type=float, help="non-negative routing buffer in kilometres")
    args = parser.parse_args()
    expanded = expand_bbox(parse_bbox(args.bbox), args.buffer_km)
    print(",".join(f"{value:.8f}" for value in expanded))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
