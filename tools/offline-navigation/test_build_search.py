import importlib.util
from pathlib import Path
import unittest


_SPEC = importlib.util.spec_from_file_location(
    "build_search", Path(__file__).with_name("build-search.py")
)
_MODULE = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(_MODULE)
_rows = _MODULE._rows
_deduplicate_rows = _MODULE._deduplicate_rows
_region_name = _MODULE._region_name


class BuildSearchTest(unittest.TestCase):
    def test_region_name_comes_from_largest_osm_settlement(self):
        features = [
            self._feature("village", "Берёзовский", "place", "village", 60.80),
            self._feature("city", "Екатеринбург", "place", "city", 60.60),
        ]

        self.assertEqual(
            "Екатеринбург",
            _region_name(features, (60.0, 56.0, 61.0, 57.0)),
        )

    def test_region_name_is_empty_when_pbf_has_no_named_settlement(self):
        self.assertIsNone(
            _region_name(
                [self._feature("shop", "Алатырь", "shop", "mall", 60.60)],
                (60.0, 56.0, 61.0, 57.0),
            )
        )

    def test_deduplicates_same_place_variants_but_keeps_distant_branches(self):
        features = [
            self._feature("food", "Алатырь", "amenity", "food_court", 60.6000),
            self._feature("mall", "Алатырь", "shop", "mall", 60.6001),
            self._feature("feature", "Алатырь", "historic", "yes", 60.6000),
            self._feature("far", "Алатырь", "shop", "mall", 60.6300),
        ]

        rows = list(_rows(features))

        self.assertEqual(2, len(rows))
        self.assertEqual("Торговый центр", rows[0][4])

    def test_cyrillic_names_are_normalized_without_merging_different_nearby_places(self):
        rows = list(_rows([
            {"properties": {"name": "Алатырь", "id": "node/1"},
             "geometry": {"type": "Point", "coordinates": [60.60, 56.84]}},
            {"properties": {"name": "Алмаз", "id": "node/2"},
             "geometry": {"type": "Point", "coordinates": [60.6002, 56.84]}},
            {"properties": {"name": "АлАТЫРЬ", "id": "node/3"},
             "geometry": {"type": "Point", "coordinates": [60.6001, 56.84]}},
        ]))

        self.assertEqual(["Алатырь", "Алмаз"], [row[0] for row in rows])

    def test_empty_names_do_not_collapse_unrelated_rows(self):
        rows = _deduplicate_rows([
            ("", "", 56.84, 60.60, "feature", "node/1"),
            ("", "", 56.84, 60.6001, "feature", "node/2"),
        ])

        self.assertEqual(2, len(rows))

    def test_search_index_contains_transliteration_and_human_place_context(self):
        rows = list(_rows([{
            "properties": {
                "name": "Алатырь",
                "id": "node/1",
                "shop": "hookah",
                "addr:street": "улица Ленина",
                "addr:housenumber": "1",
            },
            "geometry": {"type": "Point", "coordinates": [60.60, 56.84]},
        }]))

        self.assertEqual("Магазин кальянов · улица Ленина, 1", rows[0][4])
        self.assertIn("alatyr", rows[0][1])

    def test_nearby_transit_stops_in_different_directions_remain_separate(self):
        rows = list(_rows([
            self._feature("stop-a", "Алатырь", "highway", "bus_stop", 60.6000),
            self._feature("stop-b", "Алатырь", "highway", "bus_stop", 60.6002),
        ]))

        self.assertEqual(2, len(rows))

    def test_same_name_hookah_shops_without_addresses_remain_separate(self):
        rows = list(_rows([
            self._feature("shop-a", "Cosmoshop", "shop", "hookah", 60.6000),
            self._feature("shop-b", "Cosmoshop", "shop", "hookah", 60.6002),
        ]))

        self.assertEqual(2, len(rows))

    def test_nearby_generic_aliases_collapse_with_wider_place_radius(self):
        rows = list(_rows([
            self._feature("feature-a", "ТЦ «Алатырь»", "historic", "yes", 60.6000),
            self._feature("feature-b", "ТЦ «Алатырь»", "historic", "yes", 60.6010),
        ]))

        self.assertEqual(1, len(rows))

    @staticmethod
    def _feature(identifier, name, key, value, longitude):
        return {
            "properties": {"id": identifier, "name": name, key: value},
            "geometry": {"type": "Point", "coordinates": [longitude, 56.83]},
        }


if __name__ == "__main__":
    unittest.main()
