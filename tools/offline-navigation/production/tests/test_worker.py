import json
import tempfile
import unittest
from pathlib import Path

from production.config import load_config
from production.scheduler import Scheduler
from production.worker import Worker


class WorkerTest(unittest.TestCase):
    def test_worker_does_not_fabricate_source_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = root / "config.json"
            config_path.write_text(json.dumps({
                "publicRoot": str(root / "public"),
                "stagingRoot": str(root / "staging"),
                "sourceRoot": str(root / "sources"),
                "signingKey": str(root / "keys" / "signing-key.pem"),
                "regions": [{"id": "region", "sourceUrl": "https://download.example/region.pbf"}],
            }), encoding="utf-8")
            config = load_config(config_path)
            queue = root / "queue.json"
            Scheduler(config, queue).tick(1760000000)
            self.assertFalse(Worker(config, queue).run_once())
            jobs = json.loads(queue.read_text(encoding="utf-8"))["jobs"]
            self.assertEqual("failed", jobs[0]["state"])
            self.assertEqual("source_metadata_required", jobs[0]["reason"])


if __name__ == "__main__":
    unittest.main()
