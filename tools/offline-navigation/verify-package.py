#!/usr/bin/env python3
"""Verify a built regional package before it is signed and published."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import sqlite3
import tempfile
from pathlib import Path


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("package", type=Path)
    args = parser.parse_args()

    manifest_path = args.package / "manifest.unsigned.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    components = manifest["components"]
    paths = {
        "routing": args.package / "routing/valhalla-routing.tar.gz",
        "search": args.package / "search/places.sqlite.gz",
        "map": args.package / "map/ekb.pmtiles",
    }

    for name, path in paths.items():
        entry = components[name]
        if path.stat().st_size != entry["downloadBytes"]:
            raise ValueError(f"{name}: manifest size does not match {path}")
        actual = digest(path)
        if actual != entry["sha256"]:
            raise ValueError(f"{name}: manifest checksum does not match {path}")

    with tempfile.TemporaryDirectory() as directory:
        search_path = Path(directory) / "places.sqlite"
        with gzip.open(paths["search"], "rb") as source, search_path.open("wb") as target:
            target.write(source.read())
        connection = sqlite3.connect(search_path)
        try:
            count = connection.execute("SELECT count(*) FROM places").fetchone()[0]
            if count <= 0:
                raise ValueError("search index is empty")
            connection.execute("SELECT display_name FROM places WHERE places MATCH 'екб*' LIMIT 1").fetchone()
        finally:
            connection.close()

    print(f"verified {len(paths)} components and {count} FTS4 rows in {args.package}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
