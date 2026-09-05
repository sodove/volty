import unittest

from production.grid import UnsupportedCoordinate, cell_bounds, cell_id, split_cell


class GridTest(unittest.TestCase):
    def test_boundary_has_one_owner_and_children_are_deterministic(self):
        self.assertEqual("g1-090-180", cell_id(0.0, 0.0))
        self.assertEqual(cell_id(0.0, 180.0), cell_id(0.0, -180.0))
        self.assertEqual(["g1-090-180-q0", "g1-090-180-q1", "g1-090-180-q2", "g1-090-180-q3"],
                         split_cell("g1-090-180"))

    def test_polar_limit_is_unsupported(self):
        with self.assertRaises(UnsupportedCoordinate):
            cell_id(90.0, 0.0)
        self.assertEqual((0.0, 0.0, 1.0, 1.0), cell_bounds("g1-090-180"))


if __name__ == "__main__":
    unittest.main()
