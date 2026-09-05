import unittest
from datetime import datetime, timezone

from production.retention import retention_candidates


class RetentionTest(unittest.TestCase):
    def test_keeps_pinned_and_grace_period_releases(self):
        now = datetime(2026, 9, 5, tzinfo=timezone.utc).timestamp()
        registry = [
            {"id": "ru", "pinned": True, "retiredAt": 0, "good": True},
            {"id": "foreign-grace", "pinned": False, "retiredAt": now - 3600, "good": True},
            {"id": "foreign-old", "pinned": False, "retiredAt": now - 8 * 86400, "good": True},
        ]
        self.assertEqual(["foreign-old"], [item["id"] for item in retention_candidates(now, registry)])


if __name__ == "__main__":
    unittest.main()
