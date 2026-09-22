#!/usr/bin/env python3
"""Verify shipped boundaries without the original ZIP or network access."""
import csv
import json

from shapely.geometry import shape

from convert import ROOT, VERSION, EXPECTED_HASH, DISPLAY_ADJUSTMENT, adjust_shared_boundary, topology_counts, validate_geometry


def main():
    index = json.loads((ROOT / 'frontend/src/public-housing/regions/regionBoundaryIndex.json').read_text())
    provenance = json.loads((ROOT / 'frontend/public/region-boundaries' / VERSION / 'provenance.json').read_text())
    with (ROOT / 'backend/src/main/resources/region/regions.csv').open() as stream:
        catalog = {r['regionCode']: r['name'] for r in csv.DictReader(l for l in stream if not l.startswith('#'))}
    available = {e['regionCode'] for e in index['regions']}
    unavailable = {e['regionCode'] for e in index['unavailable']}
    assert index['version'] == provenance['version'] == VERSION
    assert len(available) == len(index['regions'])
    assert available.isdisjoint(unavailable)
    assert available | unavailable == catalog.keys()
    metrics = {row['regionCode']: row for row in provenance['regions']}
    baseline_root = ROOT / 'frontend/public/region-boundaries/vworld-20260909-3f8952dfda77-p2'
    baseline_geometries, published_geometries = {}, {}
    altered_codes = set(DISPLAY_ADJUSTMENT['regionCodes'])
    for entry in index['regions']:
        code = entry['regionCode']
        assert entry['name'] == catalog[code]
        path = ROOT / 'frontend/public' / entry['path'].lstrip('/')
        feature = json.loads(path.read_text())
        assert feature['type'] == 'Feature'
        assert feature['properties']['regionCode'] == code
        assert feature['properties']['version'] == index['version']
        assert feature['properties']['attribution'] == index['source']['attribution']
        geometry = shape(feature['geometry'])
        validate_geometry(geometry, geographic=True)
        bounds = list(geometry.bounds)
        assert feature['bbox'] == bounds
        assert entry['bounds'] == dict(zip(['southWestLng', 'southWestLat', 'northEastLng', 'northEastLat'], bounds))
        metric = metrics[code]
        counts = topology_counts(geometry)
        assert counts == metric['display']
        baseline = json.loads((baseline_root / f'{code}.geojson').read_text())
        baseline_geometry = shape(baseline['geometry'])
        validate_geometry(baseline_geometry, geographic=True)
        assert baseline['bbox'] == list(baseline_geometry.bounds)
        assert baseline['properties']['regionCode'] == code
        assert baseline['bbox'] == feature['bbox']
        baseline_geometries[code], published_geometries[code] = baseline_geometry, geometry
        if code in altered_codes:
            adjustment = metric['displayAdjustment']
            assert feature['properties']['displayAdjustmentId'] == DISPLAY_ADJUSTMENT['id']
            assert adjustment['before'] == metric['simplified']
            assert adjustment['after'] == counts
            assert adjustment['sourceToDisplayBoundaryDiscreteHausdorffMeters'] <= 15
            assert adjustment['sourceToDisplaySymmetricDifferenceSquareMeters'] <= 30
        else:
            assert feature['geometry'] == baseline['geometry']
            assert counts == metric['simplified']
            assert 'displayAdjustmentId' not in feature['properties']
        assert all(metric['original'][key] == counts[key] for key in ['polygons', 'holes'])
        assert metric['boundaryDiscreteHausdorffMeters'] <= 5.000001
        assert metric['geojsonBytes'] == path.stat().st_size
        if metric['method'] == 'union-catalog-children':
            assert len(metric['sourceCodes']) >= 2
            assert set(metric['sourceCodes']) <= available
    replayed, replay_audit = adjust_shared_boundary(baseline_geometries, EXPECTED_HASH)
    for code in available:
        assert replayed[code].wkb == published_geometries[code].wkb
    assert replay_audit['combinedUnionUnchanged']
    assert replay_audit['combinedUnionSymmetricDifferenceSquareMeters'] == 0
    assert replay_audit['overlapSymmetricDifferenceSquareMeters'] == 0
    assert len(provenance['displayAdjustments']) == 1
    assert provenance['displayAdjustments'][0]['sourceSha256'] == EXPECTED_HASH
    assert set(index['source']['displayAdjustments'][0]['regionCodes']) == altered_codes
    assert {r['regionCode'] for r in index['unavailable']} == {'26290', '27200', '12790'}
    assert len(provenance['sourceNameCorrections']) == 17
    assert len([r for r in provenance['geometryRepairs'] if r['accepted']]) == 3
    assert len([r for r in provenance['regions'] if r['method'] == 'union-catalog-children']) == 13
    assert metrics['12870']['simplified']['polygons'] > 800  # 신안 islands
    assert metrics['41590']['projectionFallback']['method'] == 'normalize-child-precision-then-union-without-simplification'
    assert metrics['44825']['simplified']['polygons'] == 3198  # 태안 tiny islands retained
    print(f"Verified {len(available)} official boundaries, {len(unavailable)} explicit unavailable entries, all rings/bboxes/coverage/attribution; 264 unchanged geometries and the exact paired display adjustment verified against retained p2")


if __name__ == '__main__':
    main()
