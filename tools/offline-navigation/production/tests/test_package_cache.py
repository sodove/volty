import io
import json
import unittest
from types import SimpleNamespace
from urllib.error import HTTPError
from unittest.mock import patch

from package_cache import PackageManager


class BuilderCallTest(unittest.TestCase):
    def test_builder_404_json_is_returned_as_region_state(self):
        manager = object.__new__(PackageManager)
        manager.config = SimpleNamespace(builder_url="http://offline-worker:8092")
        body = json.dumps({
            "status": "unavailable",
            "regionId": "region",
            "releaseVersion": None,
            "errorCode": "source_metadata_required",
        }).encode()
        error = HTTPError("http://offline-worker:8092/internal/builds/region", 404, "Not Found", {}, io.BytesIO(body))

        with patch("package_cache.urlopen", side_effect=error):
            result = manager._builder_call("POST", "region")

        self.assertEqual("unavailable", result["status"])
        self.assertEqual("source_metadata_required", result["errorCode"])


if __name__ == "__main__":
    unittest.main()
