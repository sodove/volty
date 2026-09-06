#!/usr/bin/env python3
"""Verify a built regional package before it is signed and published."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import sqlite3
import tarfile
import tempfile
from pathlib import Path


ROUTING_TILE_DIRECTORY = "tiles"
ROUTING_TILE_ARCHIVE = "tiles.tar"
ROUTING_ADMIN_FILE = "admins.sqlite"
ROUTING_TIMEZONE_FILE = "timezones.sqlite"
ROUTING_CONFIG_FILE = "valhalla.json"
ROUTING_REQUIRED_FILES = (
    ROUTING_TILE_ARCHIVE,
    ROUTING_ADMIN_FILE,
    ROUTING_TIMEZONE_FILE,
    ROUTING_CONFIG_FILE,
)
ROUTING_CONFIG_REFERENCES = {
    "tiles": (ROUTING_TILE_DIRECTORY, ROUTING_TILE_ARCHIVE),
    "admin": (ROUTING_ADMIN_FILE,),
    "timezone": (ROUTING_TIMEZONE_FILE,),
}


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def _referenced_filenames(value: object) -> set[str]:
    if isinstance(value, dict):
        filenames: set[str] = set()
        for item in value.values():
            filenames.update(_referenced_filenames(item))
        return filenames
    if isinstance(value, list):
        filenames: set[str] = set()
        for item in value:
            filenames.update(_referenced_filenames(item))
        return filenames
    if isinstance(value, str):
        return {value.replace("\\", "/").rstrip("/").rsplit("/", 1)[-1]}
    return set()


def verify_routing_archive(archive_path: Path, unpacked_path: Path) -> None:
    unpacked_root = unpacked_path.resolve()
    try:
        with tarfile.open(archive_path, "r:gz") as archive:
            for member in archive.getmembers():
                member_name = member.name.replace("\\", "/")
                member_parts = Path(member_name).parts
                if Path(member_name).is_absolute() or ".." in member_parts:
                    raise ValueError(f"routing archive contains unsafe path: {member.name}")
                if member.issym() or member.islnk():
                    raise ValueError(f"routing archive contains a link: {member.name}")
                if not (member.isfile() or member.isdir()):
                    raise ValueError(f"routing archive contains unsupported entry: {member.name}")
            archive.extractall(unpacked_root, filter="data")
    except (OSError, tarfile.TarError) as error:
        raise ValueError(f"routing archive cannot be unpacked: {archive_path}") from error

    missing_files = [
        filename for filename in ROUTING_REQUIRED_FILES if not (unpacked_root / filename).is_file()
    ]
    if missing_files:
        raise ValueError(
            "routing archive is missing required file(s): " + ", ".join(missing_files)
        )

    config_path = unpacked_root / ROUTING_CONFIG_FILE
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"routing archive has no readable {ROUTING_CONFIG_FILE}") from error
    referenced_filenames = _referenced_filenames(config)
    missing_references = {
        category: sorted(set(filenames) - referenced_filenames)
        for category, filenames in ROUTING_CONFIG_REFERENCES.items()
    }
    missing_references = {
        category: filenames for category, filenames in missing_references.items() if filenames
    }
    if missing_references:
        raise ValueError(
            f"{ROUTING_CONFIG_FILE} does not reference required routing file(s): "
            + "; ".join(
                f"{category}: {', '.join(filenames)}"
                for category, filenames in missing_references.items()
            )
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("package", type=Path)
    args = parser.parse_args()

    manifest_path = args.package / "manifest.unsigned.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    components = manifest["components"]
    map_files = list((args.package / "map").glob("*.pmtiles"))
    if len(map_files) != 1:
        raise ValueError(f"map: expected exactly one PMTiles file, found {len(map_files)}")
    paths = {
        "routing": args.package / "routing/valhalla-routing.tar.gz",
        "search": args.package / "search/places.sqlite.gz",
        "map": map_files[0],
    }

    for name, path in paths.items():
        entry = components[name]
        if path.stat().st_size != entry["downloadBytes"]:
            raise ValueError(f"{name}: manifest size does not match {path}")
        actual = digest(path)
        if actual != entry["sha256"]:
            raise ValueError(f"{name}: manifest checksum does not match {path}")

    with tempfile.TemporaryDirectory() as directory:
        verify_routing_archive(paths["routing"], Path(directory))

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
