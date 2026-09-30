import unittest
from unittest.mock import patch

from shapely.geometry import MultiPolygon, Polygon, box

from convert import canonical_source_name, verified_catalog_geometries, simplify_boundary, validate_geometry, boundary_discrete_hausdorff, precision_union


def entry(code, name, sigungu=None):
    return {'regionCode': code, 'name': name, 'sido': name.split()[0], 'sigungu': sigungu or ' '.join(name.split()[1:])}


def source(code, name, geometry):
    return {'regionCode': code, 'name': name, 'geometry': geometry, 'rowId': 1}


class ConversionTest(unittest.TestCase):
    def test_only_explicit_prefix_corrections_and_whitespace_are_allowed(self):
        self.assertEqual(canonical_source_name('52111', '전북특별차지도  전주시 완산구'), '전북특별자치도 전주시 완산구')
        self.assertEqual(canonical_source_name('50110', '제주도 제주시'), '제주특별자치도 제주시')
        self.assertEqual(canonical_source_name('11110', '제주도 제주시'), '제주도 제주시')

    def test_duplicate_code_never_selects_or_unions_arbitrary_rows(self):
        catalog = [entry('26290', '부산광역시 남구')]
        rows = [source('26290', '부산광역시 남구', box(0, 0, 1, 1)), source('26290', '부산광역시 남구', box(20, 20, 21, 21))]
        verified, unavailable, _ = verified_catalog_geometries(catalog, rows)
        self.assertEqual(verified, {})
        self.assertIn('duplicate', unavailable[0]['reason'])

    def test_parent_dissolve_removes_only_shared_internal_edge(self):
        catalog = [entry('41110', '경기도 수원시'), entry('41111', '경기도 수원시 장안구'), entry('41113', '경기도 수원시 권선구')]
        rows = [source('41111', catalog[1]['name'], box(0, 0, 1, 1)), source('41113', catalog[2]['name'], box(1, 0, 2, 1))]
        verified, unavailable, _ = verified_catalog_geometries(catalog, rows)
        self.assertEqual(unavailable, [])
        self.assertTrue(verified['41110']['geometry'].equals(box(0, 0, 2, 1)))
        self.assertEqual(verified['41110']['geometry'].boundary.length, 6)

    def test_missing_or_name_mismatched_child_blocks_parent(self):
        catalog = [entry('41110', '경기도 수원시'), entry('41111', '경기도 수원시 장안구'), entry('41113', '경기도 수원시 권선구')]
        for rows in [[], [source('41111', '경기도 다른시 장안구', box(0, 0, 1, 1))]]:
            verified, unavailable, _ = verified_catalog_geometries(catalog, rows)
            self.assertNotIn('41110', verified)
            self.assertEqual(len(unavailable), 3)

    def test_simplification_keeps_islands_and_holes(self):
        geometry = MultiPolygon([Polygon([(0, 0), (0, 10), (10, 10), (10, 0)], [[(2, 2), (3, 2), (3, 3), (2, 3)]]), box(20, 20, 20.1, 20.1)])
        simplified = simplify_boundary(geometry, 5)
        self.assertEqual(len(simplified.geoms), 2)
        self.assertEqual(sum(len(p.interiors) for p in simplified.geoms), 1)
        self.assertTrue(simplified.is_valid)

    def test_invalid_simplification_preserves_entire_original(self):
        original = box(0, 0, 10, 10)
        invalid = Polygon([(0, 0), (1, 1), (0, 1), (1, 0)])
        with patch('shapely.geometry.base.BaseGeometry.simplify', return_value=invalid):
            self.assertIs(simplify_boundary(original, 5), original)

    def test_indexed_hausdorff_matches_geos_for_islands_holes_and_offsets(self):
        a = MultiPolygon([Polygon([(0, 0), (0, 10), (10, 10), (10, 0)], [[(2, 2), (3, 2), (3, 3), (2, 3)]]), box(20, 20, 21, 21)])
        for b in [a, box(1, 1, 9, 9), MultiPolygon([box(-1, -1, 10, 10), box(19, 19, 21, 21)])]:
            self.assertAlmostEqual(boundary_discrete_hausdorff(a, b), a.boundary.hausdorff_distance(b.boundary), places=12)

    def test_precision_union_closes_float_sliver_but_keeps_real_hole_and_island(self):
        left = MultiPolygon([Polygon([(0, 0), (0, 10), (5, 10), (5, 0)], [[(1, 1), (2, 1), (2, 2), (1, 2)]]), box(-2, 0, -1, 1)])
        right = box(5 + 1e-9, 0, 10, 10)
        result, audits = precision_union([left, right])
        self.assertEqual(len(result.geoms), 2)
        self.assertEqual(sum(len(p.interiors) for p in result.geoms), 1)
        self.assertAlmostEqual(result.area, 100)
        self.assertLess(max(a['boundaryDiscreteHausdorffMeters'] for a in audits), 1e-7)

    def test_precision_normalization_rejects_loss_of_real_tiny_hole(self):
        original = Polygon([(0, 0), (0, 10), (10, 10), (10, 0)], [[(1, 1), (1.000000001, 1), (1.000000001, 1.000000001), (1, 1.000000001)]])
        with self.assertRaises(ValueError):
            precision_union([original])

    def test_invalid_or_out_of_range_geojson_fails(self):
        with self.assertRaises(ValueError):
            validate_geometry(Polygon([(0, 0), (1, 1), (0, 1), (1, 0)]), geographic=True)
        with self.assertRaises(ValueError):
            validate_geometry(box(200, 30, 201, 31), geographic=True)


if __name__ == '__main__':
    unittest.main()
