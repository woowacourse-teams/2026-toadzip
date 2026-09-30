import unittest

from shapely.geometry import MultiPolygon, Polygon, box

from convert import EXPECTED_HASH, adjust_shared_boundary


TRIPLE = [(10, 11), (8, 10), (10, 9)]
CONTRACT = {'id': 'fixture-paired-tip', 'regionCodes': ['east', 'west'], 'sourceSha256': EXPECTED_HASH,
            'triple': TRIPLE, 'maxBoundaryDisplacementMeters': 15, 'maxSymmetricDifferenceSquareMeters': 30}


def fixture(holes=False):
    east = Polygon([(5, 0), (20, 0), (20, 20), (10, 20), *TRIPLE, (10, 5), (5, 5)],
                   [[(15, 2), (15, 3), (16, 3), (16, 2)]] if holes else [])
    west = Polygon([(0, 0), (5, 0), (5, 5), (10, 5), *TRIPLE[::-1], (10, 20), (0, 20)],
                   [[(2, 2), (2, 3), (3, 3), (3, 2)]] if holes else [])
    return {'east': east, 'west': west, 'unrelated': box(40, 40, 50, 50)}


def adjust(geometries, contract=CONTRACT, source_hash=EXPECTED_HASH):
    return adjust_shared_boundary(geometries, source_hash, contract=contract, project=lambda x, y: (x, y))


class DisplayAdjustmentTest(unittest.TestCase):
    def test_paired_tip_only_preserves_union_overlap_bbox_and_other_corners(self):
        original = fixture()
        result, audit = adjust(original)
        self.assertTrue(result['east'].union(result['west']).equals(original['east'].union(original['west'])))
        self.assertEqual(result['east'].intersection(result['west']).area, 0)
        for code in ['east', 'west']:
            before, after = list(original[code].exterior.coords), list(result[code].exterior.coords)
            self.assertEqual(after, [p for p in before if p != TRIPLE[1]])
            self.assertEqual(original[code].bounds, result[code].bounds)
            self.assertTrue(result[code].is_valid)
        self.assertEqual(result['east'].area - original['east'].area, -2)
        self.assertEqual(result['west'].area - original['west'].area, 2)
        self.assertIs(result['unrelated'], original['unrelated'])
        self.assertEqual(audit['combinedUnionSymmetricDifferenceSquareMeters'], 0)
        self.assertEqual(audit['overlapSymmetricDifferenceSquareMeters'], 0)

    def test_retains_every_hole_and_disconnected_island(self):
        original = fixture(holes=True)
        original['east'] = MultiPolygon([original['east'], box(30, 0, 31, 1)])
        result, _ = adjust(original)
        self.assertEqual(len(result['east'].geoms), 2)
        self.assertEqual(list(result['east'].geoms[0].interiors[0].coords), list(original['east'].geoms[0].interiors[0].coords))
        self.assertEqual(result['east'].geoms[1].wkb, original['east'].geoms[1].wkb)
        self.assertEqual(list(result['west'].interiors[0].coords), list(original['west'].interiors[0].coords))

    def test_missing_counterpart_cannot_mutate_one_side(self):
        original = fixture()
        del original['west']
        before = original['east'].wkb
        with self.assertRaisesRegex(ValueError, 'both'):
            adjust(original)
        self.assertEqual(original['east'].wkb, before)

    def test_changed_point_or_regular_corner_is_not_approximately_matched(self):
        original = fixture()
        before = {k: g.wkb for k, g in original.items()}
        for wrong_triple in [[TRIPLE[0], (8.000000001, 10), TRIPLE[2]], [(5, 0), (20, 0), (20, 20)]]:
            with self.assertRaises(ValueError):
                adjust(original, {**CONTRACT, 'triple': wrong_triple})
        self.assertEqual(before, {k: g.wkb for k, g in original.items()})

    def test_wrong_source_hash_rejected_before_any_adjustment(self):
        with self.assertRaisesRegex(ValueError, 'SHA'):
            adjust(fixture(), source_hash='different-source')

    def test_one_side_already_adjusted_rejects_entire_pair(self):
        original = fixture()
        original['west'] = Polygon([p for p in original['west'].exterior.coords if p != TRIPLE[1]])
        with self.assertRaises(ValueError):
            adjust(original)

    def test_displacement_and_area_limits_fail_closed(self):
        for constraint in [{'maxBoundaryDisplacementMeters': 1}, {'maxSymmetricDifferenceSquareMeters': 1}]:
            with self.assertRaisesRegex(ValueError, 'limit'):
                adjust(fixture(), {**CONTRACT, **constraint})

    def test_actual_hole_inside_tip_is_not_dropped(self):
        original = fixture()
        original['east'] = Polygon(original['east'].exterior.coords,
                                   [[(8.5, 9.95), (8.5, 10.05), (8.6, 10.05), (8.6, 9.95)]])
        self.assertTrue(original['east'].is_valid)
        with self.assertRaises(ValueError):
            adjust(original)

    def test_changed_bbox_rejected(self):
        original = fixture()
        original['east'] = Polygon([(10, 0), (20, 0), (20, 20), (10, 20), *TRIPLE, (10, 0)])
        with self.assertRaisesRegex(ValueError, 'bbox'):
            adjust(original)

    def test_third_region_intersecting_adjustment_rejected(self):
        original = fixture()
        original['third-party'] = box(8.5, 9.95, 8.6, 10.05)
        with self.assertRaisesRegex(ValueError, 'third'):
            adjust(original)


if __name__ == '__main__':
    unittest.main()
