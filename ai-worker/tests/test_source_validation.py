"""같은 원문 셀에서 검증되지 않은 모델 수치를 확정하지 않는다."""
import pytest
from ai_worker import announcement, pages


def extract(rows, item):
    ids = [f'P1:T1:R{i + 1}' for i in range(1, len(rows))]
    selected = pages.PromptPages(
        text='', rental_rows=0, supply_text='', supply_pages=[1], supply_row_ids=frozenset(ids),
        supply_row_text={key: '\t'.join(row) for key, row in zip(ids, rows[1:])},
        supply_row_values={key: pages.table_row_field_values(rows, i) for i, key in enumerate(ids, 1)},
        notice_text='', notice_pages=[], complex_candidates=[], table_structure_fallback=False,
        ambiguous_supply_tables=False,
    )
    return announcement.compact_supply_tree({'data': {'rows': [{
        'rowIds': ids, 'complexName': 'Test', 'housingType': rows[1][1], 'target': '청년', **item,
    }]}}, selected)


@pytest.mark.parametrize('header', ['잔여예비자', '기존예비자', '현재예비자', '대기예비자'])
def test_existing_waitlist_is_not_new_reserve_recruitment(header):
    result = extract([['단지명', '주택형', '공급대상', header], ['Test', '26A', '청년', '20']], {'reserveCount': 20})
    assert result['data']['supplyRows'][0]['targets'][0]['reserveCount'] is None
    assert any(x['path'].endswith('.reserveCount') for x in result['data']['unresolvedFields'])


@pytest.mark.parametrize(('header', 'value', 'expected'), [
    ('임대보증금(원)', 46920, 46920), ('임대보증금(원)', 46920000, None),
    ('임대보증금(천원)', 46920000, 46920000), ('임대보증금(천원)', 46920, None),
    ('임대보증금', 46920000, None),
])
def test_money_validation_obeys_explicit_source_unit(header, value, expected):
    result = extract([['단지명', '주택형', '공급대상', header], ['Test', '26A', '청년', '46,920']], {'rentalDeposit': value})
    assert result['data']['supplyRows'][0]['targets'][0]['rentalDeposit'] == expected


@pytest.mark.parametrize(('source_type', 'area', 'expected'), [('26A/B', 999.99, None), ('26A', 999.99, None), ('26A', 26.37, 26.37)])
def test_subtype_area_requires_matching_subtype_and_column(source_type, area, expected):
    result = extract([
        ['단지명', '주택형', '공급대상', '전용면적', '주거공용', '건설호수'],
        ['Test', '26A/B', '청년', '', '', ''],
        ['Test', source_type, '청년', '26.37', '15.1', '10'],
    ], {'subtypes': [{'name': '26A', 'exclusiveArea': area, 'commonArea': 888, 'constructedHouseholdCount': 9999}]})
    subtype = result['data']['supplyRows'][0]['subtypes'][0]
    assert subtype['exclusiveArea'] == expected
    assert subtype['commonArea'] is None
    assert subtype['constructedHouseholdCount'] is None
    assert any('.subtypes[0].commonArea' in x['path'] for x in result['data']['unresolvedFields'])
