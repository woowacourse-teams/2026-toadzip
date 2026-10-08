"""Import public code.go.kr query exports (ZIP containing XLSX), without dependencies.

Usage: python3 scripts/regions/import_catalog.py /tmp/toadzip-regions-source
Downloads must use disuseAt=0, chkHigh=0, chkOrder=0, chkLow=0, one ZIP per province.
Province display order: MOIS localGovernment/screen.do, checked 2026-10-08.
"""
import csv
import hashlib
import io
import json
import re
import html
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PROVINCES = ['11', '12', '26', '27', '28', '30', '31', '36', '41', '51', '43', '44', '52', '47', '48', '50']
NS = {'s': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}


def read_export(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != 1 or not names[0].endswith('.xlsx'):
            raise ValueError(f'Unexpected ZIP: {path}')
        with zipfile.ZipFile(io.BytesIO(archive.read(names[0]))) as workbook:
            strings = [''.join(node.itertext()) for node in ET.fromstring(
                workbook.read('xl/sharedStrings.xml')).findall('s:si', NS)]
            result = []
            for row in ET.fromstring(workbook.read('xl/worksheets/sheet1.xml')).findall('.//s:row', NS):
                values = []
                for cell in row:
                    value = cell.find('s:v', NS)
                    values.append(strings[int(value.text)] if cell.get('t') == 's' else (value.text if value is not None else ''.join(cell.itertext())))
                result.append(values)
            if '폐지구분' in result[0]:
                result = [row[:2] + row[3:] for row in result]
            if result[0] != ['법정동코드', '법정동명', '상위지역코드', '서열', '최하지역명']:
                raise ValueError('Unexpected export header')
            return result[1:]


def build(source):
    rows = {}
    hashes = {}
    for province in PROVINCES:
        path = source / f'{province}.zip'
        hashes[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        for row in read_export(path):
            code, name, parent, rank, short_name = row
            if len(code) != 10 or not code.isdigit() or not code.startswith(province) or code in rows:
                raise ValueError(f'Invalid/duplicate code: {code}')
            rows[code] = (name.strip(), parent, int(rank), short_name.strip())
    canonical = list(csv.DictReader((ROOT / 'backend/src/main/resources/region/regions.csv').open()))
    canonical = [r for r in canonical if not r['regionCode'].startswith('#')]
    districts = {r['regionCode']: r for r in canonical}
    ministry_path = source / 'mois-local-government.html'
    ministry = ministry_path.read_text()
    hashes[ministry_path.name] = hashlib.sha256(ministry_path.read_bytes()).hexdigest()
    sections = re.findall(r'<h3 class="location_title">.*?</h3>(.*?)(?=<h3 class="location_title">|$)', ministry, re.S)
    province_names = re.findall(r'<h3 class="location_title"><a[^>]*>(.*?)</a></h3>', ministry, re.S)
    province_names = [name.strip() for name in province_names]
    if province_names != [next(r['sido'] for r in canonical if r['regionCode'].startswith(p)) for p in PROVINCES]:
        raise ValueError('MOIS province order changed; review PROVINCES')
    district_ranks = {}
    for province, section in zip(province_names, sections):
        listing = re.search(r'<ul class="location_list">(.*?)</ul>', section, re.S)
        names = [] if listing is None else re.findall(r'<a[^>]*>(.*?)</a>', listing.group(1), re.S)
        for rank, name in enumerate(names, 1):
            district_ranks[(province, html.unescape(name).strip())] = rank
    def district_order(code):
        district = districts[code]
        province = district['sido']
        name = district['sigungu']
        key = f'{PROVINCES.index(code[:2]):02d}'
        if code == '36110':
            return key + '.0000'
        municipal_name = name.split(' ')[0]
        rank = district_ranks.get((province, municipal_name))
        if rank is None:
            raise ValueError(f'Missing MOIS district: {province} {name}')
        key += f'.{rank:04d}'
        if ' ' in name:
            key += '.' + code
        return key
    def order(code):
        if code.endswith('00000'):
            return district_order(code[:5])
        return district_order(code[:5]) + f'.{rows[code][2]:04d}.{code}'
    items = []
    for province in PROVINCES:
        name = next(r['sido'] for r in canonical if r['regionCode'].startswith(province))
        items.append(dict(regionCode=province, provinceName=name, districtName=None,
                          displayName=name + ' 전체', sortKey=f'{PROVINCES.index(province):02d}'))
    for code, district in districts.items():
        full_code = code + '00000'
        if full_code not in rows:
            raise ValueError(f'District absent from official export: {code}')
        items.append(dict(regionCode=code, provinceName=district['sido'], districtName=district['sigungu'],
                          displayName=district['name'], sortKey=order(full_code)))
    neighborhoods = []
    for code, (name, parent, rank, short_name) in rows.items():
        if not code.endswith('00') or code.endswith('00000'):
            continue
        district = districts.get(code[:5])
        if district is None or parent != code[:5] + '00000':
            raise ValueError(f'Unmatched neighborhood parent: {code} {parent}')
        if not name.startswith(district['sido'] + ' '):
            raise ValueError(f'Unmatched province name: {code}')
        neighborhoods.append([code, district['sido'], district['sigungu'], name])
        items.append(dict(regionCode=code, provinceName=district['sido'], districtName=district['sigungu'],
                          displayName=name, sortKey=order(code)))
    aliases = []
    alias_rows = list(csv.DictReader(line for line in (ROOT / 'backend/src/main/resources/region/region-code-aliases.csv').open() if not line.startswith('#')))
    district_aliases = {r['legacyRegionCode']: r['currentRegionCode'] for r in alias_rows}
    current_by_name = {(code[:5], value[3]): code for code, value in rows.items()
                       if code.endswith('00') and not code.endswith('00000')}
    for province in ['29', '46']:
        path = source / f'{province}.zip'
        hashes[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
        for old_code, name, parent, rank, short_name in read_export(path):
            district = district_aliases.get(old_code[:5])
            if not district or not old_code.endswith('00') or old_code.endswith('00000'):
                continue
            current = current_by_name.get((district, short_name.strip()))
            if current:
                aliases.append([old_code, current])
    items.sort(key=lambda r: (r['sortKey'], r['regionCode']))
    provenance = dict(acquiredAt='2026-10-08',
                      source='https://www.code.go.kr/stdcode/regCodeL.do?menuNo=101010100010',
                      provinceOrderSource='https://www.mois.go.kr/frt/sub/a04/localGovernment/screen.do',
                      query='disuseAt=0&chkHigh=0&chkOrder=0&chkLow=0', sha256=hashes)
    target = ROOT / 'backend/src/main/resources/region'
    for name, header, data in [
        ('neighborhood-code-aliases.csv', ['legacyRegionCode,currentRegionCode'], sorted(aliases)),
        ('neighborhoods.csv', ['regionCode,sido,sigungu,name'], sorted(neighborhoods)),
        ('display-order.csv', ['regionCode,sortKey'], [[r['regionCode'], r['sortKey']] for r in items]),
    ]:
        with (target / name).open('w') as output:
            output.write(header[0] + '\n')
            csv.writer(output, lineterminator='\n').writerows(data)
    (target / 'catalog-provenance.json').write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + '\n')
    frontend = ROOT / 'frontend/src/public-housing/regions/regionCatalog.json'
    frontend.write_text(json.dumps(dict(provinceOrder=PROVINCES, items=items), ensure_ascii=False, separators=(',', ':')) + '\n')
    print(f'{len(neighborhoods)} neighborhoods, {len(items)} total regions')


if __name__ == '__main__':
    build(Path(sys.argv[1]))
