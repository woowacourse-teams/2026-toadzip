#!/usr/bin/env python3
"""Verify shipped boundaries without the original ZIP or network access."""
import csv
import json

from shapely.geometry import shape

from convert import ROOT, VERSION, topology_counts, validate_geometry


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
        assert counts == metric['simplified']
        assert all(metric['original'][key] == counts[key] for key in ['polygons', 'holes'])
        assert metric['boundaryDiscreteHausdorffMeters'] <= 5.000001
        assert metric['geojsonBytes'] == path.stat().st_size
        if metric['method'] == 'union-catalog-children':
            assert len(metric['sourceCodes']) >= 2
            assert set(metric['sourceCodes']) <= available
    assert {r['regionCode'] for r in index['unavailable']} == {'26290', '27200', '12790'}
    assert len(provenance['sourceNameCorrections']) == 17
    assert len([r for r in provenance['geometryRepairs'] if r['accepted']]) == 3
    assert len([r for r in provenance['regions'] if r['method'] == 'union-catalog-children']) == 13
    assert metrics['12870']['simplified']['polygons'] > 800  # 신안 islands
    assert metrics['41590']['projectionFallback']['method'] == 'normalize-child-precision-then-union-without-simplification'
    assert metrics['44825']['simplified']['polygons'] == 3198  # 태안 tiny islands retained
    print(f"Verified {len(available)} official boundaries, {len(unavailable)} explicit unavailable entries, all rings/bboxes/coverage/attribution")


if __name__ == '__main__':
    main()
