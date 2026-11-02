import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent))
import generate


class CreditsCatalogTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.about_path = self.root / "aboutlibraries.json"
        self.graph_path = self.root / "dependencies.json"
        self.registry_path = self.root / "registry.json"

    def tearDown(self):
        self.temp.cleanup()

    def write_text(self, relative_path, content):
        path = self.root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="")
        return path

    def write_json(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")

    def seed_inputs(self):
        self.write_json(
            self.about_path,
            {
                "libraries": [
                    {
                        "uniqueId": "com.example:alpha",
                        "artifactVersion": "1.2.3",
                        "name": "Alpha library",
                        "developers": [
                            {"name": " Alice "},
                            {"name": ""},
                            {"name": "N/A"},
                        ],
                        "organization": {"name": "Example Org"},
                        "website": "https://example.test/alpha",
                        "licenses": ["Apache-2.0"],
                    },
                    {
                        "uniqueId": "com.example:transitive-only",
                        "artifactVersion": "9.0",
                        "name": "Should be excluded",
                        "licenses": ["Unused-License"],
                    },
                ],
                "licenses": {
                    "Apache-2.0": {
                        "name": "Apache License",
                        "content": "Apache text\r\nsecond line\rthird line",
                    },
                    "Unused-License": {
                        "name": "Unused license retained",
                        "content": "Unused text",
                    },
                },
            },
        )
        self.write_json(
            self.graph_path,
            ["com.example:alpha:1.2.3", "file:local-copy.aar"],
        )
        self.write_text("licenses/mit.txt", "MIT terms\r\nsecond line\r")
        self.write_text("licenses/bsd.txt", "BSD terms\n")
        self.write_text("notices/local.txt", "Local notice\r\nline two\r")
        self.write_text("notices/alpha.txt", "Alpha notice\n")
        self.write_text("third-party/source/NOTICE.txt", "Original source notice\r\n")
        (self.root / "binaries").mkdir()
        (self.root / "binaries/local.jar").write_bytes(b"\x00JAR\r\x00bytes")
        self.write_json(
            self.registry_path,
            {
                "projects": [
                    {
                        "id": "manual:local-copy",
                        "name": "Local Copy",
                        "artifact": "file:local-copy.aar",
                        "licenseIds": ["MIT", "BSD-3-Clause"],
                        "noticePath": "notices/local.txt",
                    },
                    {
                        "id": "manual:copied-code",
                        "name": "Copied Code",
                        "licenseIds": ["MIT"],
                    },
                ],
                "licenseOverrides": {},
                "licenses": {
                    "MIT": {"name": "MIT", "path": "licenses/mit.txt"},
                    "BSD-3-Clause": {
                        "name": "BSD 3-Clause",
                        "path": "licenses/bsd.txt",
                    },
                },
                "libraryOverrides": {
                    "com.example:alpha": {
                        "licenseIds": ["Apache-2.0", "MIT"],
                        "noticePath": "notices/alpha.txt",
                    }
                },
                "inputs": ["third-party/source/NOTICE.txt"],
                "binaryInputs": ["binaries/local.jar"],
            },
        )

    def build(self):
        return generate.build_catalog(
            self.root,
            self.about_path,
            self.graph_path,
            self.registry_path,
        )

    def test_intersects_runtime_graph_and_preserves_manual_multi_license_projects(self):
        self.seed_inputs()

        catalog = self.build()

        projects = {project["id"]: project for project in catalog["projects"]}
        self.assertEqual(
            set(projects),
            {"com.example:alpha", "manual:local-copy", "manual:copied-code"},
        )
        self.assertEqual(projects["com.example:alpha"]["version"], "1.2.3")
        self.assertEqual(projects["com.example:alpha"]["authors"], ["Alice", "Example Org"])
        self.assertEqual(projects["com.example:alpha"]["licenseIds"], ["Apache-2.0", "MIT"])
        self.assertEqual(projects["com.example:alpha"]["notice"], "Alpha notice\n")
        self.assertEqual(
            projects["manual:local-copy"]["licenseIds"], ["BSD-3-Clause", "MIT"]
        )
        self.assertEqual(projects["manual:local-copy"]["notice"], "Local notice\nline two\n")
        self.assertEqual(set(catalog["licenses"]), {"Apache-2.0", "Unused-License", "MIT", "BSD-3-Clause"})
        self.assertEqual(catalog["licenses"]["MIT"]["content"], "MIT terms\nsecond line\n")
        self.assertNotIn("com.example:transitive-only", projects)

    def test_stale_metadata_version_fails(self):
        self.seed_inputs()
        about = json.loads(self.about_path.read_text())
        about["libraries"][0]["artifactVersion"] = "old-version"
        self.write_json(self.about_path, about)
        with self.assertRaisesRegex(generate.CatalogError, "version"):
            self.build()

    def test_empty_runtime_graph_fails(self):
        self.seed_inputs()
        self.write_json(self.graph_path, [])
        with self.assertRaisesRegex(generate.CatalogError, "empty"):
            self.build()

    def test_removed_local_artifact_is_not_kept_as_copied_source(self):
        self.seed_inputs()
        self.write_json(self.graph_path, ["com.example:alpha:1.2.3"])
        catalog = self.build()
        self.assertNotIn("manual:local-copy", {p["id"] for p in catalog["projects"]})

    def test_runtime_coordinate_without_metadata_or_manual_artifact_fails(self):
        self.seed_inputs()
        graph = json.loads(self.graph_path.read_text(encoding="utf-8"))
        graph.append("com.missing:runtime:1.0")
        self.write_json(self.graph_path, graph)

        with self.assertRaisesRegex(generate.CatalogError, "com.missing:runtime:1.0"):
            self.build()

    def test_runtime_project_without_license_ids_fails(self):
        self.seed_inputs()
        about = json.loads(self.about_path.read_text(encoding="utf-8"))
        about["libraries"][0]["licenses"] = []
        self.write_json(self.about_path, about)
        registry = json.loads(self.registry_path.read_text(encoding="utf-8"))
        registry["libraryOverrides"]["com.example:alpha"].pop("licenseIds")
        self.write_json(self.registry_path, registry)

        with self.assertRaisesRegex(generate.CatalogError, "license"):
            self.build()

    def test_license_without_content_or_local_override_fails(self):
        self.seed_inputs()
        about = json.loads(self.about_path.read_text(encoding="utf-8"))
        about["licenses"]["Apache-2.0"].pop("content")
        self.write_json(self.about_path, about)
        registry = json.loads(self.registry_path.read_text(encoding="utf-8"))
        registry["libraryOverrides"].pop("com.example:alpha")
        self.write_json(self.registry_path, registry)

        with self.assertRaisesRegex(generate.CatalogError, "Apache-2.0"):
            self.build()

    def test_license_override_supplies_local_content_for_incomplete_metadata(self):
        self.seed_inputs()
        about = json.loads(self.about_path.read_text(encoding="utf-8"))
        about["licenses"]["Apache-2.0"].pop("content")
        self.write_json(self.about_path, about)
        registry = json.loads(self.registry_path.read_text(encoding="utf-8"))
        registry["licenseOverrides"] = {
            "Apache-2.0": {"name": "Apache Two", "path": "licenses/mit.txt"}
        }
        self.write_json(self.registry_path, registry)

        catalog = self.build()

        self.assertEqual(catalog["licenses"]["Apache-2.0"]["name"], "Apache Two")
        self.assertEqual(catalog["licenses"]["Apache-2.0"]["content"], "MIT terms\nsecond line\n")

    def test_registry_and_all_referenced_local_text_files_are_normalized_and_hashed(self):
        self.seed_inputs()

        catalog = self.build()

        inputs = catalog["sources"]["inputs"]
        for relative in (
            "registry.json",
            "licenses/mit.txt",
            "licenses/bsd.txt",
            "notices/local.txt",
            "notices/alpha.txt",
            "third-party/source/NOTICE.txt",
        ):
            normalized = (self.root / relative).read_bytes().decode("utf-8")
            normalized = normalized.replace("\r\n", "\n").replace("\r", "\n")
            expected = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
            self.assertEqual(inputs[relative], expected)
        self.assertEqual(
            catalog["sources"]["binaries"]["binaries/local.jar"],
            hashlib.sha256(b"\x00JAR\r\x00bytes").hexdigest(),
        )

    def test_output_order_is_stable_by_name_then_id(self):
        self.seed_inputs()
        registry = json.loads(self.registry_path.read_text(encoding="utf-8"))
        registry["projects"].extend(
            [
                {"id": "manual:z", "name": "Same", "licenseIds": ["MIT"]},
                {"id": "manual:a", "name": "Same", "licenseIds": ["MIT"]},
            ]
        )
        self.write_json(self.registry_path, registry)

        first = self.build()
        second = self.build()

        names_and_ids = [(project["name"], project["id"]) for project in first["projects"]]
        self.assertEqual(names_and_ids, sorted(names_and_ids, key=lambda item: (item[0].casefold(), item[1])))
        self.assertEqual(json.dumps(first, sort_keys=True), json.dumps(second, sort_keys=True))

    def test_registry_path_traversal_is_rejected(self):
        self.seed_inputs()
        registry = json.loads(self.registry_path.read_text(encoding="utf-8"))
        registry["licenses"]["MIT"]["path"] = "../outside.txt"
        self.write_json(self.registry_path, registry)

        with self.assertRaisesRegex(generate.CatalogError, "path|outside|traversal"):
            self.build()

    def test_duplicate_manual_project_ids_are_rejected(self):
        self.seed_inputs()
        registry = json.loads(self.registry_path.read_text(encoding="utf-8"))
        registry["projects"].append(
            {"id": "manual:copied-code", "name": "Duplicate", "licenseIds": ["MIT"]}
        )
        self.write_json(self.registry_path, registry)

        with self.assertRaisesRegex(generate.CatalogError, "manual:copied-code"):
            self.build()


if __name__ == "__main__":
    unittest.main()
