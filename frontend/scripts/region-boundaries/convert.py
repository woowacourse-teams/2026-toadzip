#!/usr/bin/env python3
"""Convert the audited VWorld SIG snapshot; no network access or guessed codes."""
import argparse
import collections
import csv
import hashlib
import io
import json
import math
from pathlib import Path
import zipfile

import numpy as np
import pyproj
import shapefile
import shapely
from shapely.geometry import mapping, shape
from shapely.geometry.polygon import orient
from shapely.ops import transform, unary_union
from shapely.validation import explain_validity, make_valid

EXPECTED_HASH = '3f8952dfda7763c2c594adb7a9f7ace1f40b996235fae6ef0d0f90f794dbce97'
VERSION = 'vworld-20260909-3f8952dfda77-p2'
SIMPLIFICATION_METERS = 5
SOURCE_URL = 'https://www.vworld.kr/dtmk/dtmk_ntads_s002.do?svcCde=NA&dsId=21'
ROOT = Path(__file__).resolve().parents[3]


def normalized_name(name):
    return ' '.join(name.split())


def canonical_source_name(code, name):
    name = normalized_name(name)
    # Explicit source spelling corrections, never a code or geometry remapping.
    corrections = {'52': ('전북특별차지도 ', '전북특별자치도 '), '50': ('제주도 ', '제주특별자치도 ')}
    if code[:2] in corrections:
        old, new = corrections[code[:2]]
        if name.startswith(old):
            return new + name[len(old):]
    return name


def polygon_parts(geometry):
    return [geometry] if geometry.geom_type == 'Polygon' else list(geometry.geoms)


def topology_counts(geometry):
    polygons = polygon_parts(geometry)
    return {'polygons': len(polygons), 'holes': sum(len(p.interiors) for p in polygons),
            'positions': int(shapely.get_num_coordinates(geometry))}


def validate_geometry(geometry, geographic=False):
    if geometry.geom_type not in ('Polygon', 'MultiPolygon') or geometry.is_empty or not geometry.is_valid:
        raise ValueError(f'invalid polygon: {explain_validity(geometry)} ({geometry.geom_type})')
    for polygon in polygon_parts(geometry):
        for ring in [polygon.exterior, *polygon.interiors]:
            coordinates = list(ring.coords)
            if len(coordinates) < 4 or coordinates[0] != coordinates[-1]:
                raise ValueError('unclosed or short ring')
            for x, y in coordinates:
                if not math.isfinite(x) or not math.isfinite(y):
                    raise ValueError('non-finite position')
                if geographic and not (-180 <= x <= 180 and -90 <= y <= 90):
                    raise ValueError('position outside WGS84 bounds')


def boundary_discrete_hausdorff(a, b):
    """Same vertex-to-segment metric as GEOS, indexed to avoid quadratic scans."""
    def directed(first, second):
        segments = []
        for polygon in polygon_parts(second):
            for ring in [polygon.exterior, *polygon.interiors]:
                coordinates = np.asarray(ring.coords)
                segments.extend(np.stack([coordinates[:-1], coordinates[1:]], axis=1))
        tree = shapely.STRtree(shapely.linestrings(np.asarray(segments)))
        _, distances = tree.query_nearest(shapely.points(shapely.get_coordinates(first)),
                                          return_distance=True, all_matches=False)
        return float(np.max(distances))
    result = max(directed(a, b), directed(b, a))
    if not math.isfinite(result):
        raise ValueError('non-finite boundary displacement')
    return result


def precision_union(children, grid=1e-7):
    """Normalize floating precision, retaining each child's islands and holes."""
    normalized, audits = [], []
    for child in children:
        fixed = shapely.set_precision(child, grid)
        validate_geometry(fixed)
        before, after = topology_counts(child), topology_counts(fixed)
        if any(before[key] != after[key] for key in ('polygons', 'holes')):
            raise ValueError('precision normalization changes source islands or holes')
        distance = boundary_discrete_hausdorff(child, fixed)
        if distance > math.sqrt(2) * grid / 2 + 1e-10:
            raise ValueError('precision displacement exceeds half-grid diagonal')
        audits.append({'original': before, 'normalized': after,
                       'boundaryDiscreteHausdorffMeters': distance,
                       'relativeAreaChange': abs(fixed.area - child.area) / child.area})
        normalized.append(fixed)
    geometry = shapely.union_all(normalized, grid_size=grid)
    validate_geometry(geometry)
    return geometry, audits


def simplify_boundary(geometry, tolerance):
    validate_geometry(geometry)
    simplified = geometry.simplify(tolerance, preserve_topology=True)
    # GEOS can still produce invalid nested shells on tiny coastal polygons.
    # Keep the complete original instead of repairing/dropping simplified parts.
    try:
        validate_geometry(simplified)
    except ValueError:
        return geometry
    before, after = topology_counts(geometry), topology_counts(simplified)
    if any(before[key] != after[key] for key in ('polygons', 'holes')):
        return geometry
    return simplified


def verified_catalog_geometries(catalog, rows):
    grouped = collections.defaultdict(list)
    for row in rows:
        grouped[row['regionCode']].append(row)
    verified, failures, corrections = {}, {}, []
    for entry in catalog:
        code, name = entry['regionCode'], entry['name']
        matches = grouped.get(code, [])
        if not matches:
            continue
        if len(matches) != 1:
            failures[code] = 'duplicate-source-code: ambiguous geometries'
            continue
        row = matches[0]
        if canonical_source_name(code, row['name']) != normalized_name(name):
            failures[code] = f'source-name-mismatch: {row["name"]}'
        elif row.get('error'):
            failures[code] = row['error']
        else:
            verified[code] = {**row, 'name': name, 'method': 'source', 'sourceCodes': [code]}
            if normalized_name(row['name']) != normalized_name(name):
                corrections.append({'regionCode': code, 'sourceName': row['name'], 'canonicalName': name,
                                    'reason': 'explicit province-prefix spelling correction; code and remaining name identical'})
    for entry in catalog:
        code = entry['regionCode']
        if code in grouped:
            continue
        # Use catalog hierarchy, not arbitrary numerical prefixes or old code aliases.
        children = [c for c in catalog if c['sido'] == entry['sido']
                    and c['sigungu'].startswith(entry['sigungu'] + ' ')
                    and c['sigungu'][len(entry['sigungu']) + 1:].endswith('구')
                    and ' ' not in c['sigungu'][len(entry['sigungu']) + 1:]]
        if not children:
            failures[code] = 'missing-source-code'
        elif any(child['regionCode'] not in verified for child in children):
            failures[code] = 'unavailable-child-boundary'
        else:
            geometry = unary_union([verified[c['regionCode']]['geometry'] for c in children])
            validate_geometry(geometry)
            verified[code] = {'regionCode': code, 'name': entry['name'], 'geometry': geometry,
                              'method': 'union-catalog-children', 'sourceCodes': [c['regionCode'] for c in children]}
    unavailable = [{'regionCode': e['regionCode'], 'name': e['name'], 'reason': failures[e['regionCode']]}
                   for e in catalog if e['regionCode'] not in verified]
    return verified, unavailable, corrections


def read_source(path):
    with zipfile.ZipFile(path) as outer:
        nested_name = 'AL_D001_00_20260909(SIG).zip'
        nested = outer.read(nested_name)
    with zipfile.ZipFile(io.BytesIO(nested)) as archive:
        data = {Path(name).suffix: archive.read(name) for name in archive.namelist()}
    if data['.cpg'].decode().strip() != '949':
        raise ValueError('unexpected source DBF encoding')
    crs = pyproj.CRS.from_wkt(data['.prj'].decode())
    expected_crs = pyproj.CRS.from_epsg(5186)
    # ESRI datum spelling is not recognized as an EPSG alias by PROJ 9.3.0.
    # Compare the declared datum and numeric projection/ellipsoid, not names.
    parameters = lambda value: {p.code: p.value for p in value.coordinate_operation.params}
    if (crs.datum.name != 'D_Korea_Geodetic_Datum_2002'
            or crs.coordinate_operation.method_code != '9807'
            or parameters(crs) != parameters(expected_crs)
            or crs.ellipsoid.semi_major_metre != expected_crs.ellipsoid.semi_major_metre
            or crs.ellipsoid.inverse_flattening != expected_crs.ellipsoid.inverse_flattening
            or crs.prime_meridian.longitude != 0
            or any(axis.unit_conversion_factor != 1 for axis in crs.axis_info)):
        raise ValueError('source PRJ parameters do not match EPSG:5186')
    reader = shapefile.Reader(shp=io.BytesIO(data['.shp']), shx=io.BytesIO(data['.shx']),
                             dbf=io.BytesIO(data['.dbf']), encoding='cp949')
    rows, repairs = [], []
    for item in reader.iterShapeRecords():
        record = item.record.as_dict()
        code = record['A1']
        if len(code) != 5 or not code.isdigit() or code != record['A4']:
            raise ValueError('source code fields disagree or are not five digits')
        geometry = shape(item.shape.__geo_interface__)
        row = {'regionCode': code, 'name': record['A2'], 'rowId': record['A0'], 'geometry': geometry,
               'recordDate': str(record['A3'])}
        if not geometry.is_valid:
            reason = explain_validity(geometry)
            fixed = make_valid(geometry)
            audit = {'regionCode': code, 'reason': reason, 'original': topology_counts(geometry),
                     'resultType': fixed.geom_type}
            if fixed.geom_type not in ('Polygon', 'MultiPolygon'):
                row['error'] = f'invalid-source-geometry: {reason}; repair contains non-polygon remnants'
                audit['accepted'] = False
            else:
                relative_area = abs(fixed.area - geometry.area) / geometry.area
                boundary_distance = boundary_discrete_hausdorff(geometry, fixed)
                audit.update({'result': topology_counts(fixed), 'relativeAreaChange': relative_area,
                              'boundaryHausdorffMeters': boundary_distance, 'bboxUnchanged': geometry.bounds == fixed.bounds})
                accepted = (fixed.is_valid and geometry.bounds == fixed.bounds and relative_area < 1e-8
                            and math.isfinite(boundary_distance) and boundary_distance < 1e-7)
                audit['accepted'] = accepted
                if accepted:
                    row['geometry'] = fixed
                else:
                    row['error'] = f'invalid-source-geometry: {reason}; repair exceeds audited limits'
            repairs.append(audit)
        rows.append(row)
    return rows, repairs, {'nestedArchive': nested_name, 'nestedSha256': hashlib.sha256(nested).hexdigest(),
                           'sourcePrj': data['.prj'].decode(), 'dbfEncoding': 'CP949', 'dbfFields': reader.fields[1:]}


def write_immutable(path, value):
    content = (json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False) + '\n').encode()
    if path.exists() and path.read_bytes() != content:
        raise ValueError(f'immutable output differs: {path}; increment processing version')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return len(content)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source_zip', type=Path)
    parser.add_argument('--acquired-at', required=True, help='Recorded acquisition timestamp, not dataset reference date')
    parser.add_argument('--output-root', type=Path, default=ROOT)
    args = parser.parse_args()
    digest = hashlib.file_digest(args.source_zip.open('rb'), 'sha256').hexdigest()
    if digest != EXPECTED_HASH:
        raise ValueError('source SHA-256 differs from the audited snapshot')
    catalog_path = ROOT / 'backend/src/main/resources/region/regions.csv'
    with catalog_path.open() as stream:
        catalog = list(csv.DictReader(line for line in stream if not line.startswith('#')))
    rows, repairs, raw_metadata = read_source(args.source_zip)
    verified, unavailable, corrections = verified_catalog_geometries(catalog, rows)
    transformer = pyproj.Transformer.from_crs(5186, 4326, always_xy=True)
    source = {'dataset': '국토교통부/K-Geo플랫폼 법정구역정보', 'url': SOURCE_URL,
              'attribution': '국토교통부/K-Geo플랫폼 · VWorld 법정구역정보, CC BY 2.0 KR; 두꺼비집 가공',
              'license': 'CC BY 2.0 KR', 'licenseUrl': 'https://creativecommons.org/licenses/by/2.0/kr/',
              'referenceDate': '2026-09-09', 'updateDate': '2026-09-09', 'acquiredAt': args.acquired_at,
              'acquiredAtEvidence': 'local downloaded ZIP filesystem creation timestamp',
              'sha256': digest, 'sourceBytes': args.source_zip.stat().st_size, 'sourceCrs': 'EPSG:5186',
              'outputCrs': 'EPSG:4326', 'simplificationToleranceMeters': SIMPLIFICATION_METERS,
              'processingVersion': 'p2', 'provenancePath': f'/region-boundaries/{VERSION}/provenance.json'}
    entries, metrics = [], []
    target = args.output_root / 'frontend/public/region-boundaries' / VERSION
    for code, row in sorted(verified.items()):
        geometry = row['geometry']
        validate_geometry(geometry)
        simplified = simplify_boundary(geometry, SIMPLIFICATION_METERS)
        # Exact GEOS discrete Hausdorff metric on ring vertices, no densification.
        hausdorff = boundary_discrete_hausdorff(geometry, simplified)
        wgs84 = transform(transformer.transform, simplified)
        projection_audit = None
        if not wgs84.is_valid:
            raw_wgs84 = transform(transformer.transform, geometry)
            if raw_wgs84.is_valid:
                simplified, wgs84, hausdorff = geometry, raw_wgs84, 0.0
                projection_audit = {'method': 'unsimplified-source-after-projection',
                                    'reason': explain_validity(transform(transformer.transform, geometry.simplify(SIMPLIFICATION_METERS, preserve_topology=True)))}
            elif row['method'] == 'union-catalog-children':
                # Remove union slivers by bounded precision normalization,
                # never by filtering holes or islands according to their size.
                normalized_union, child_audits = precision_union(
                    [verified[c]['geometry'] for c in row['sourceCodes']])
                wgs84 = transform(transformer.transform, normalized_union)
                validate_geometry(wgs84, geographic=True)
                projection_audit = {'method': 'normalize-child-precision-then-union-without-simplification',
                                    'reason': explain_validity(raw_wgs84), 'precisionGridMeters': 1e-7,
                                    'originalUnion': topology_counts(geometry),
                                    'normalizedUnion': topology_counts(normalized_union),
                                    'children': [{'regionCode': code, **audit} for code, audit in zip(row['sourceCodes'], child_audits)],
                                    'relativeAreaChange': abs(normalized_union.area - geometry.area) / geometry.area}
                if projection_audit['relativeAreaChange'] > 1e-8:
                    raise ValueError('precision union changes area beyond audited tolerance')
                geometry = simplified = normalized_union
                hausdorff = 0.0
        # Full float precision avoids losing tiny islands to decimal rounding.
        if wgs84.geom_type == 'Polygon':
            wgs84 = orient(wgs84, sign=1)
        else:
            wgs84 = shapely.MultiPolygon([orient(p, sign=1) for p in wgs84.geoms])
        validate_geometry(wgs84, geographic=True)
        bounds = list(wgs84.bounds)
        feature = {'type': 'Feature', 'bbox': bounds,
                   'properties': {'regionCode': code, 'version': VERSION, 'name': row['name'],
                                  'attribution': source['attribution']}, 'geometry': mapping(wgs84)}
        size = write_immutable(target / f'{code}.geojson', feature)
        entries.append({'regionCode': code, 'name': row['name'],
                        'bounds': {'southWestLat': bounds[1], 'southWestLng': bounds[0],
                                   'northEastLat': bounds[3], 'northEastLng': bounds[2]},
                        'path': f'/region-boundaries/{VERSION}/{code}.geojson'})
        metrics.append({'regionCode': code, 'name': row['name'], 'method': row['method'],
                        'sourceCodes': row['sourceCodes'], 'original': topology_counts(geometry),
                        'simplified': topology_counts(simplified), 'geojsonBytes': size,
                        'simplificationApplied': simplified is not geometry,
                        'projectionFallback': projection_audit,
                        'simplificationFallback': 'invalid geometry or changed polygon/hole count' if simplified is geometry else None,
                        'boundaryDiscreteHausdorffMeters': hausdorff,
                        'relativeAreaChange': abs(simplified.area - geometry.area) / geometry.area})
        print(f'{code} {size} bytes', flush=True)
    grouped = collections.defaultdict(list)
    for row in rows:
        grouped[row['regionCode']].append(row)
    duplicates = [{'regionCode': code, 'rows': [{'rowId': r['rowId'], 'name': r['name'],
                    'recordDate': r['recordDate'], 'projectedBbox': list(r['geometry'].bounds)} for r in group]}
                  for code, group in grouped.items() if len(group) > 1]
    provenance = {'version': VERSION, 'source': {**source, **raw_metadata},
                  'catalogSha256': hashlib.file_digest(catalog_path.open('rb'), 'sha256').hexdigest(),
                  'tools': {'python': '3.12', 'pyshp': shapefile.__version__, 'pyproj': pyproj.__version__,
                            'proj': pyproj.proj_version_str, 'shapely': shapely.__version__,
                            'geos': shapely.geos_version_string, 'transform': transformer.description},
                  'coverage': {'catalogRegions': len(catalog), 'sourceRows': len(rows),
                               'sourceUniqueCodes': len(grouped), 'available': len(entries),
                               'unavailable': len(unavailable), 'scope': '5-digit 시군구; 2-digit 시도 unsupported'},
                  'sourceNameCorrections': corrections, 'geometryRepairs': repairs,
                  'duplicateSourceCodes': duplicates, 'sourceCodesOutsideCatalog': sorted(set(grouped) - {e['regionCode'] for e in catalog}),
                  'unavailable': unavailable, 'regions': metrics}
    write_immutable(target / 'provenance.json', provenance)
    index = args.output_root / 'frontend/src/public-housing/regions/regionBoundaryIndex.json'
    # Index changes to point at new immutable snapshots on subsequent releases.
    index.parent.mkdir(parents=True, exist_ok=True)
    index.write_text(json.dumps({'version': VERSION, 'regions': entries, 'unavailable': unavailable,
                                'source': source}, ensure_ascii=False, separators=(',', ':'), allow_nan=False) + '\n')
    print(json.dumps(provenance['coverage'], ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
