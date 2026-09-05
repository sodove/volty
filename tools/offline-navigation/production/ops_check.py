"""Fail-fast configuration check used before a remote deployment."""

from __future__ import annotations

import argparse
from pathlib import Path

from .config import load_config


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("config", type=Path)
    args = parser.parse_args()
    config = load_config(args.config)
    if not config.signing_key.is_file():
        raise SystemExit(f"signing key is missing: {config.signing_key}")
    if not config.regions:
        raise SystemExit("no production regions configured; add canonical region entries before starting the worker")
    for region in config.regions:
        if not region.bbox:
            raise SystemExit(f"{region.id}: bbox is required; do not use a guessed country bbox")
    print(f"configuration valid: {len(config.regions)} regions; data roots are separated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
