import json
import tempfile
import unittest
from pathlib import Path

from production.bootstrap import enqueue_inventory, plan_inventory


class BootstrapTest(unittest.TestCase):
    def fixture(self):
        return {
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "properties": {"id": "russia", "name": "Russia", "urls": {"pbf": "https://download.example/russia.osm.pbf"}},
                    "geometry": {"type": "Polygon", "coordinates": [[[60, 56], [62, 56], [62, 58], [60, 58], [60, 56]]]}
                },
                {
                    "type": "Feature",
                    "properties": {"id": "russia", "name": "Russia", "urls": {"pbf": "https://download.example/russia.osm.pbf"}},
                    "geometry": {"type": "Polygon", "coordinates": [[[60, 56], [62, 56], [62, 58], [60, 58], [60, 56]]]}
                },
                {
                    "type": "Feature",
                    "properties": {"id": "kazakhstan", "name": "Kazakhstan", "urls": {"pbf": "https://download.example/kazakhstan.osm.pbf"}},
                    "geometry": {"type": "Polygon", "coordinates": [[[64, 55], [66, 55], [66, 57], [64, 57], [64, 55]]]}
                }
            ]
        }

    def test_plan_creates_only_mask_intersecting_cells_and_public_sources(self):
        inventory = plan_inventory(self.fixture(), "russia")
        ids = {item["regionId"] for item in inventory["regions"]}
        self.assertEqual({"g1-146-240", "g1-146-241", "g1-147-240", "g1-147-241"}, ids)
        for item in inventory["regions"]:
            self.assertEqual(["russia"], item["sourceIds"])
            self.assertTrue(item["sourceUrls"][0].startswith("https://"))
        self.assertEqual(4, inventory["summary"]["plannedRegions"])

    def test_duplicate_source_features_are_deduplicated(self):
        inventory = plan_inventory(self.fixture(), "russia")
        self.assertEqual(["russia"], inventory["regions"][0]["sourceIds"])

    def test_enqueue_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inventory = plan_inventory(self.fixture(), "russia")
            first = enqueue_inventory(inventory, root / "jobs.json")
            second = enqueue_inventory(inventory, root / "jobs.json")
            self.assertEqual(4, len(first))
            self.assertEqual([], second)
            state = json.loads((root / "jobs.json").read_text(encoding="utf-8"))
            self.assertEqual(4, len(state["jobs"]))
            self.assertEqual({"russia"}, {job["sourceId"] for job in state["jobs"]})


if __name__ == "__main__":
    unittest.main()
