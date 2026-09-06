#!/usr/bin/env python3
"""Normalize generated Valhalla config for the pinned runtime service."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


AUTO_PEDESTRIAN_SERVICE_LIMIT = {
    "max_distance": 5_000_000.0,
    "max_locations": 20,
    "max_matrix_distance": 400_000.0,
    "max_matrix_location_pairs": 2_500,
}


def ensure_auto_pedestrian_limit(config: dict[str, object]) -> bool:
    """Add the service limit expected by the pinned Valhalla service if absent."""
    service_limits = config.setdefault("service_limits", {})
    if not isinstance(service_limits, dict):
        raise ValueError("service_limits must be an object")
    if "auto_pedestrian" in service_limits:
        return False
    service_limits["auto_pedestrian"] = dict(AUTO_PEDESTRIAN_SERVICE_LIMIT)
    return True


def normalize_file(path: Path) -> bool:
    config = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(config, dict):
        raise ValueError("Valhalla config must be a JSON object")
    changed = ensure_auto_pedestrian_limit(config)
    if changed:
        path.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
    return changed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("config", type=Path)
    args = parser.parse_args()
    changed = normalize_file(args.config)
    print("added service_limits.auto_pedestrian" if changed else "config already normalized")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
