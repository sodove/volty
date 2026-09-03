import gzip
import hashlib
import importlib.util
import json
import math
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).parent
EXPAND_SPEC = importlib.util.spec_from_file_location("expand_bbox", ROOT / "expand-bbox.py")
assert EXPAND_SPEC is not None and EXPAND_SPEC.loader is not None
EXPAND_MODULE = importlib.util.module_from_spec(EXPAND_SPEC)
EXPAND_SPEC.loader.exec_module(EXPAND_MODULE)
VERIFY_SCRIPT = ROOT / "verify-package.py"
VERIFY_SPEC = importlib.util.spec_from_file_location("verify_package", VERIFY_SCRIPT)
assert VERIFY_SPEC is not None and VERIFY_SPEC.loader is not None
VERIFY_MODULE = importlib.util.module_from_spec(VERIFY_SPEC)
VERIFY_SPEC.loader.exec_module(VERIFY_MODULE)


def complete_config() -> dict[str, object]:
    return {
        "mjolnir": {
            "tile_dir": "/work/tiles",
            "tile_extract": "/work/tiles.tar",
            "admin": "/work/admins.sqlite",
            "timezone": "/work/timezones.sqlite",
        }
    }


def write_routing_archive(
    path: Path,
    config: dict[str, object],
    *,
    include_timezone: bool,
    missing_files: set[str] | None = None,
) -> None:
    missing_files = missing_files or set()
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        (root / "tiles.tar").write_bytes(b"tiles")
        (root / "admins.sqlite").write_bytes(b"admins")
        (root / "valhalla.json").write_text(json.dumps(config), encoding="utf-8")
        if include_timezone:
            (root / "timezones.sqlite").write_bytes(b"timezones")
        with tarfile.open(path, "w:gz") as archive:
            for name in ("tiles.tar", "admins.sqlite", "timezones.sqlite", "valhalla.json"):
                member = root / name
                if member.exists() and name not in missing_files:
                    archive.add(member, arcname=name)


def write_package(
    root: Path,
    *,
    config: dict[str, object],
    include_timezone: bool,
    missing_files: set[str] | None = None,
) -> None:
    routing = root / "routing/valhalla-routing.tar.gz"
    search = root / "search/places.sqlite.gz"
    map_file = root / "map/test.pmtiles"
    routing.parent.mkdir(parents=True)
    search.parent.mkdir(parents=True)
    map_file.parent.mkdir(parents=True)

    write_routing_archive(
        routing,
        config,
        include_timezone=include_timezone,
        missing_files=missing_files,
    )
    database = root / "places.sqlite"
    connection = VERIFY_MODULE.sqlite3.connect(database)
    try:
        connection.execute("CREATE VIRTUAL TABLE places USING fts4(display_name)")
        connection.execute("INSERT INTO places(display_name) VALUES ('екб')")
        connection.commit()
    finally:
        connection.close()
    with gzip.open(search, "wb") as compressed:
        compressed.write(database.read_bytes())
    map_file.write_bytes(b"pmtiles")

    def component(path: Path) -> dict[str, object]:
        return {
            "downloadBytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }

    (root / "manifest.unsigned.json").write_text(
        json.dumps(
            {
                "components": {
                    "routing": component(routing),
                    "search": component(search),
                    "map": component(map_file),
                }
            }
        ),
        encoding="utf-8",
    )


class OfflineNavigationToolchainTest(unittest.TestCase):
    def test_routing_bbox_expands_logical_bbox_by_requested_buffer(self):
        bbox = EXPAND_MODULE.expand_bbox(
            EXPAND_MODULE.parse_bbox("59.10,56.00,61.90,57.55"),
            20.0,
        )

        self.assertLess(bbox[0], 59.10)
        self.assertLess(bbox[1], 56.00)
        self.assertGreater(bbox[2], 61.90)
        self.assertGreater(bbox[3], 57.55)

    def test_routing_bbox_clamps_to_world_bounds(self):
        bbox = EXPAND_MODULE.expand_bbox(
            EXPAND_MODULE.parse_bbox("179.9,89.9,180.0,90.0"),
            20.0,
        )

        self.assertEqual(180.0, bbox[2])
        self.assertEqual(90.0, bbox[3])
        self.assertTrue(all(math.isfinite(value) for value in bbox))

    def test_build_package_includes_timezone_database_in_routing_archive(self):
        script = (ROOT / "build-package.sh").read_text(encoding="utf-8")

        self.assertIn(
            'valhalla_timezone_run() {\n'
            '  docker run --rm --network host --workdir /work --user "$(id -u):$(id -g)" \\\n'
            '    -v "$STAGING:/work" -v "$PARENT:/input:ro" \\\n'
            '    "$VALHALLA_IMAGE" "$@"\n'
            '}\n\n'
            'echo "Extracting logical region with routing buffer"',
            script,
        )
        self.assertIn(
            'ROUTING_BBOX=$(python3 "$SCRIPT_DIR/expand-bbox.py" "$BBOX" "$ROUTING_BUFFER_KM")',
            script,
        )
        self.assertIn(
            'tools_run osmium extract --bbox "$ROUTING_BBOX" --strategy=smart',
            script,
        )
        self.assertNotIn(
            "--network host",
            script.split("valhalla_timezone_run()", 1)[0],
        )
        self.assertIn(
            'valhalla_timezone_run valhalla_build_timezones > "$STAGING/installed/routing/timezones.sqlite"\n'
            "valhalla_run valhalla_build_config",
            script,
        )
        self.assertIn(
            "tiles.tar admins.sqlite timezones.sqlite valhalla.json",
            script,
        )

    def test_verifier_rejects_routing_archive_without_timezone_database(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_package(
                root,
                config=complete_config(),
                include_timezone=False,
            )

            with patch.object(sys, "argv", [str(VERIFY_SCRIPT), str(root)]):
                with self.assertRaisesRegex(ValueError, "timezones.sqlite"):
                    VERIFY_MODULE.main()

    def test_verifier_rejects_routing_archive_without_tiles_archive(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_package(
                root,
                config=complete_config(),
                include_timezone=True,
                missing_files={"tiles.tar"},
            )

            with patch.object(sys, "argv", [str(VERIFY_SCRIPT), str(root)]):
                with self.assertRaisesRegex(ValueError, "tiles.tar"):
                    VERIFY_MODULE.main()

    def test_verifier_rejects_routing_archive_without_admin_database(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_package(
                root,
                config=complete_config(),
                include_timezone=True,
                missing_files={"admins.sqlite"},
            )

            with patch.object(sys, "argv", [str(VERIFY_SCRIPT), str(root)]):
                with self.assertRaisesRegex(ValueError, "admins.sqlite"):
                    VERIFY_MODULE.main()

    def test_verifier_rejects_routing_archive_without_timezone_reference(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_package(
                root,
                config={
                    "mjolnir": {
                        "tile_dir": "/work/tiles",
                        "tile_extract": "/work/tiles.tar",
                        "admin": "/work/admins.sqlite",
                    }
                },
                include_timezone=True,
            )

            with patch.object(sys, "argv", [str(VERIFY_SCRIPT), str(root)]):
                with self.assertRaisesRegex(ValueError, "timezones.sqlite"):
                    VERIFY_MODULE.main()

    def test_verifier_rejects_routing_config_without_tiles_reference(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_package(
                root,
                config={
                    "mjolnir": {
                        "tile_extract": "/work/tiles.tar",
                        "admin": "/work/admins.sqlite",
                        "timezone": "/work/timezones.sqlite",
                    }
                },
                include_timezone=True,
            )

            with patch.object(sys, "argv", [str(VERIFY_SCRIPT), str(root)]):
                with self.assertRaisesRegex(ValueError, "tiles"):
                    VERIFY_MODULE.main()

    def test_verifier_rejects_routing_config_without_admin_reference(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_package(
                root,
                config={
                    "mjolnir": {
                        "tile_dir": "/work/tiles",
                        "tile_extract": "/work/tiles.tar",
                        "timezone": "/work/timezones.sqlite",
                    }
                },
                include_timezone=True,
            )

            with patch.object(sys, "argv", [str(VERIFY_SCRIPT), str(root)]):
                with self.assertRaisesRegex(ValueError, "admins.sqlite"):
                    VERIFY_MODULE.main()

    def test_verifier_accepts_routing_archive_with_timezone_database_and_reference(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_package(
                root,
                config=complete_config(),
                include_timezone=True,
            )

            with patch.object(sys, "argv", [str(VERIFY_SCRIPT), str(root)]):
                self.assertEqual(VERIFY_MODULE.main(), 0)


if __name__ == "__main__":
    unittest.main()
