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
REPOSITORY_ROOT = ROOT.parent.parent

# Task 8 removes these files only after the Android/device acceptance gate. The
# guard below deliberately excludes this exact, temporary compatibility set so
# that it can prove the live Kotlin/worker graph is clean without pretending
# that the deferred files have already been deleted.
DEFERRED_LEGACY_MAP_PATHS = frozenset(
    {
        "composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflineMapSource.kt",
        "composeApp/src/androidMain/kotlin/ru/sodovaya/volty/data/navigation/offline/AndroidOfflinePmtilesTileServer.kt",
        "composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/map/OfflineMapStylePolicy.kt",
        "tools/offline-navigation/process.lua",
    }
)
DEFERRED_LEGACY_MAP_DIRECTORIES = frozenset(
    {
        "composeApp/src/androidMain/assets/offline-map-styles",
        "composeApp/src/androidMain/assets/offline-map-glyphs",
    }
)
FORBIDDEN_LIVE_MAP_REFERENCES = (
    "AndroidOfflineMapSource",
    "AndroidOfflinePmtilesTileServer",
    "OfflineMapStylePolicy",
    "tilemaker",
    "process.lua",
    "map.mbtiles",
    "components.map",
)


def _is_deferred_legacy_map_path(path: Path) -> bool:
    relative = path.relative_to(REPOSITORY_ROOT).as_posix()
    return relative in DEFERRED_LEGACY_MAP_PATHS or any(
        relative == directory or relative.startswith(f"{directory}/")
        for directory in DEFERRED_LEGACY_MAP_DIRECTORIES
    )


def _live_production_source_files() -> list[Path]:
    roots = (
        REPOSITORY_ROOT / "composeApp/src/androidMain/kotlin",
        REPOSITORY_ROOT / "composeApp/src/commonMain/kotlin",
        ROOT / "production",
    )
    suffixes = {".kt", ".kts", ".py", ".sh"}
    return sorted(
        path
        for source_root in roots
        if source_root.exists()
        for path in source_root.rglob("*")
        if path.is_file()
        and path.suffix in suffixes
        and not _is_deferred_legacy_map_path(path)
    )


def _live_map_reference_hits() -> list[str]:
    hits: list[str] = []
    for path in _live_production_source_files():
        text = path.read_text(encoding="utf-8")
        relative = path.relative_to(REPOSITORY_ROOT).as_posix()
        for forbidden in FORBIDDEN_LIVE_MAP_REFERENCES:
            if forbidden in text:
                hits.append(f"{relative}: {forbidden}")
    return hits


EXPAND_SPEC = importlib.util.spec_from_file_location("expand_bbox", ROOT / "expand-bbox.py")
assert EXPAND_SPEC is not None and EXPAND_SPEC.loader is not None
EXPAND_MODULE = importlib.util.module_from_spec(EXPAND_SPEC)
EXPAND_SPEC.loader.exec_module(EXPAND_MODULE)
VERIFY_SCRIPT = ROOT / "verify-package.py"
VERIFY_SPEC = importlib.util.spec_from_file_location("verify_package", VERIFY_SCRIPT)
assert VERIFY_SPEC is not None and VERIFY_SPEC.loader is not None
VERIFY_MODULE = importlib.util.module_from_spec(VERIFY_SPEC)
VERIFY_SPEC.loader.exec_module(VERIFY_MODULE)
SEARCH_SPEC = importlib.util.spec_from_file_location("build_search", ROOT / "build-search.py")
assert SEARCH_SPEC is not None and SEARCH_SPEC.loader is not None
SEARCH_MODULE = importlib.util.module_from_spec(SEARCH_SPEC)
SEARCH_SPEC.loader.exec_module(SEARCH_MODULE)
CONFIG_SPEC = importlib.util.spec_from_file_location(
    "normalize_valhalla_config",
    ROOT / "normalize-valhalla-config.py",
)
assert CONFIG_SPEC is not None and CONFIG_SPEC.loader is not None
CONFIG_MODULE = importlib.util.module_from_spec(CONFIG_SPEC)
CONFIG_SPEC.loader.exec_module(CONFIG_MODULE)


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
    routing.parent.mkdir(parents=True)
    search.parent.mkdir(parents=True)

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
    def component(path: Path) -> dict[str, object]:
        return {
            "downloadBytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }

    (root / "manifest.unsigned.json").write_text(
        json.dumps(
            {
                "schemaVersion": 3,
                "source": {
                    "osmReplicationSequence": 1,
                    "osmTimestamp": "2026-09-07T00:00:00Z",
                    "sourceId": "test-source",
                    "sourceUrl": "https://download.example/test.osm.pbf",
                    "sourceSha256": "a" * 64,
                },
                "components": {
                    "routing": component(routing),
                    "search": component(search),
                }
            }
        ),
        encoding="utf-8",
    )


class OfflineNavigationToolchainTest(unittest.TestCase):
    def test_live_kotlin_and_worker_have_no_legacy_map_pipeline_references(self):
        hits = _live_map_reference_hits()

        self.assertEqual(
            [],
            hits,
            "live production sources reference the deferred map pipeline: "
            + "; ".join(hits),
        )

    def test_build_package_is_navigation_only(self):
        script = (ROOT / "build-package.sh").read_text(encoding="utf-8")

        self.assertNotIn("tilemaker", script)
        self.assertNotIn("map.mbtiles", script)
        self.assertNotIn("PMTILES_IMAGE", script)
        self.assertNotIn('mkdir -p "$STAGING/map"', script)

    def test_v3_verifier_rejects_map_component_in_new_package(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_package(root, config=complete_config(), include_timezone=True)
            map_file = root / "map/test.pmtiles"
            map_file.parent.mkdir()
            map_file.write_bytes(b"legacy-map")
            manifest_path = root / "manifest.unsigned.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["components"]["map"] = {
                "downloadBytes": map_file.stat().st_size,
                "installedBytes": map_file.stat().st_size,
                "sha256": hashlib.sha256(map_file.read_bytes()).hexdigest(),
            }
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            with patch.object(sys, "argv", [str(VERIFY_SCRIPT), str(root)]):
                with self.assertRaisesRegex(ValueError, "map"):
                    VERIFY_MODULE.main()

    def test_v3_manifest_includes_complete_source_provenance(self):
        script = ROOT / "build-manifest.py"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            routing = root / "routing.tar.gz"
            routing.write_bytes(b"routing")
            search = root / "search.sqlite.gz"
            search.write_bytes(b"search")
            routing_installed = root / "routing-installed"
            search_installed = root / "search-installed"
            routing_installed.mkdir()
            search_installed.mkdir()
            with patch.object(sys, "argv", [
                str(script), "--output", str(root / "manifest.json"),
                "--routing", str(routing), "--routing-installed", str(routing_installed),
                "--search", str(search), "--search-installed", str(search_installed),
                "--region-id", "region", "--release-version", "release",
                "--min-app-version-code", "1", "--osm-sequence", "1",
                "--osm-timestamp", "2026-09-07T00:00:00Z", "--bbox", "0,0,1,1",
                "--source-id", "region-source",
                "--source-url", "https://download.geofabrik.de/region.osm.pbf",
                "--source-sha256", "a" * 64,
            ]):
                import runpy
                with self.assertRaises(SystemExit) as exit_info:
                    runpy.run_path(str(script), run_name="__main__")
                self.assertEqual(0, exit_info.exception.code)
            manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(3, manifest["schemaVersion"])
            self.assertEqual("https://download.geofabrik.de/region.osm.pbf", manifest["source"]["sourceUrl"])
            self.assertEqual("a" * 64, manifest["source"]["sourceSha256"])

    def test_normalizer_adds_auto_pedestrian_service_limit(self):
        config = {
            "service_limits": {
                "auto": {
                    "max_distance": 5000000.0,
                    "max_locations": 20,
                    "max_matrix_distance": 400000.0,
                    "max_matrix_location_pairs": 2500,
                },
            },
        }

        changed = CONFIG_MODULE.ensure_auto_pedestrian_limit(config)

        self.assertTrue(changed)
        self.assertEqual(
            {
                "max_distance": 5000000.0,
                "max_locations": 20,
                "max_matrix_distance": 400000.0,
                "max_matrix_location_pairs": 2500,
            },
            config["service_limits"]["auto_pedestrian"],
        )

    def test_normalizer_does_not_overwrite_existing_limit(self):
        existing = {
            "max_distance": 123.0,
            "max_matrix_distance": 456.0,
            "max_matrix_location_pairs": 7,
        }
        config = {"service_limits": {"auto_pedestrian": existing.copy()}}

        changed = CONFIG_MODULE.ensure_auto_pedestrian_limit(config)

        self.assertFalse(changed)
        self.assertEqual(existing, config["service_limits"]["auto_pedestrian"])

    def test_search_index_folds_russian_yo_without_changing_display_name(self):
        features = [
            {
                "properties": {"name:ru": "Ёлка", "addr:city": "Екатеринбург"},
                "geometry": {"type": "Point", "coordinates": [60.6, 56.8]},
            }
        ]

        rows = list(SEARCH_MODULE._rows(features))

        self.assertEqual("Ёлка", rows[0][0])
        self.assertEqual("елка екатеринбург", rows[0][1])

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
            '}',
            script,
        )
        self.assertIn('echo "Extracting logical region for search and routing"', script)
        self.assertIn(
            'ROUTING_BBOX=$(python3 "$SCRIPT_DIR/expand-bbox.py" "$BBOX" "$ROUTING_BUFFER_KM")',
            script,
        )
        self.assertIn(
            'tools_run osmium tags-filter \\\n'
            '  "/input/$INPUT_NAME" nwr/highway route=ferry type=restriction \\\n'
            '  -o /work/routing-source.osm.pbf',
            script,
        )
        self.assertIn(
            'tools_run osmium extract --bbox "$ROUTING_BBOX" --strategy=complete_ways',
            script,
        )
        self.assertIn(
            'valhalla_run valhalla_build_tiles -c /work/installed/routing/valhalla.json -j "$THREADS" /work/routing.osm.pbf',
            script,
        )
        self.assertIn(
            'valhalla_run valhalla_build_admins -c /work/installed/routing/valhalla.json \\\n'
            '  "/input/$INPUT_NAME"',
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
            'python3 "$SCRIPT_DIR/normalize-valhalla-config.py" \\\n'
            '  "$STAGING/installed/routing/valhalla.json"',
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
