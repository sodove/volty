import unittest

from production.resource_budget import admit_build


class ResourceBudgetTest(unittest.TestCase):
    def test_build_is_rejected_when_live_service_reserve_would_be_breached(self):
        decision = admit_build({"ram": 8, "disk": 100}, {"ram": 4, "disk": 20}, {"ram": 6, "disk": 10})
        self.assertFalse(decision.allowed)
        self.assertEqual("ram_reserve", decision.reason)

    def test_build_is_allowed_when_all_budgets_fit(self):
        decision = admit_build({"ram": 16, "disk": 100}, {"ram": 4, "disk": 20}, {"ram": 6, "disk": 10})
        self.assertTrue(decision.allowed)


if __name__ == "__main__":
    unittest.main()
