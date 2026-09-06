import tempfile
import unittest
from pathlib import Path

from production.source_cache import SourceError, public_pbf_url, validate_public_url


class SourceCacheTest(unittest.TestCase):
    def test_only_public_pbf_url_is_accepted(self):
        index = {"features": [{"properties": {"id": "russia", "urls": {
            "pbf": "https://download.geofabrik.de/russia-latest.osm.pbf",
            "pbf-internal": "https://10.0.0.1/russia.pbf",
        }}}]}
        self.assertEqual("https://download.geofabrik.de/russia-latest.osm.pbf", public_pbf_url(index, "russia"))

    def test_internal_or_credentialed_url_is_rejected(self):
        for value in ("https://127.0.0.1/a.pbf", "https://user:pass@example.com/a.pbf", "http://example.com/a.pbf"):
            with self.subTest(value=value):
                with self.assertRaises(SourceError):
                    validate_public_url(value)


if __name__ == "__main__":
    unittest.main()
