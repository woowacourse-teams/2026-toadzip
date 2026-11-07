import json

import pytest

from ai_worker import checks, pages, supply_assembly, tables


def test_compact_supply_rows_are_assembled_from_cell_values_into_manager_contract():
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[4],
        supply_row_ids=frozenset({"P4:T1:R4", "P4:T1:R5"}),
        supply_row_text={"P4:T1:R4": "Test\t26\t26A\t청년\t20", "P4:T1:R5": "Test\t26\t26A\t고령자\t3"},
        notice_text="body", notice_pages=[1], complex_candidates=["Test"], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={
            "P4:T1:R4": {"complexName": "Test", "housingType": "26A", "target": "청년", "reserveCount": "20",
                          "exclusiveArea": "26", "commonArea": "15", "totalHouseholdCount": "100"},
            "P4:T1:R5": {"complexName": "Test", "housingType": "26A", "target": "고령자", "reserveCount": "3",
                          "totalHouseholdCount": "100"},
        },
    )
    compact = {"data": {"rows": [
        {"rowIds": ["P4:T1:R4"], "complexName": "Test", "housingType": "26A", "target": "청년",
         "reserveCount": 20, "exclusiveArea": 26, "commonArea": 15},
        {"rowIds": ["P4:T1:R5"], "complexName": "Test", "housingType": "26A", "target": "고령자", "reserveCount": 3},
    ]}}

    tree, _ = supply_assembly.assemble(compact, selected)

    assert len(tree["data"]["supplyRows"]) == 1
    row = tree["data"]["supplyRows"][0]
    assert row["complexReference"]["sourceComplexName"] == "Test"
    assert row["sourceHousingTypeName"] == "26A"
    assert [(target["target"], target["reserveCount"]) for target in row["targets"]] == [("청년", 20), ("고령자", 3)]
    assert tree["data"]["complexes"] == [{
        "sourceComplexName": "Test", "address": None, "totalHouseholdCount": 100, "parkingCount": None,
    }]
    assert {item["path"] for item in tree["evidence"]} >= {
        "complexes[0]", "supplyRows[0]", "supplyRows[0].targets[0]", "supplyRows[0].targets[1]",
    }


def test_compact_target_removes_extraction_whitespace_around_parentheses():
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[5],
        supply_row_ids=frozenset({"P5:T1:R38"}),
        supply_row_text={"P5:T1:R38": "Sample Complex\t18A2\t청년 ( 주거약자)\t3"},
        notice_text="body", notice_pages=[1], complex_candidates=["Sample Complex"],
        table_structure_fallback=False, ambiguous_supply_tables=False,
        supply_row_values={"P5:T1:R38": {
            "complexName": "Sample Complex", "housingType": "18A2", "target": "청년 ( 주거약자)",
            "reserveCount": "3",
        }},
    )
    compact = {"data": {"rows": [{
        "rowIds": ["P5:T1:R38"], "complexName": "Sample Complex", "housingType": "18A2",
        "target": "청년 ( 주거약자)", "reserveCount": 3,
    }]}}

    tree, _ = supply_assembly.assemble(compact, selected)
    row = tree["data"]["supplyRows"][0]
    warnings = reviewed(tree)

    assert row["targets"][0]["target"] == "청년(주거약자)"
    assert not any("target: 표기 규칙 위반" in warning for warning in warnings)
    # 같은 검산은 괄호 앞 공백이 남은 대상 이름을 표기 규칙 위반으로 알린다.
    row["targets"][0]["target"] = "청년 (주거약자)"
    assert "supplyRows[0].targets[0].target: 표기 규칙 위반(청년 (주거약자))" in reviewed(tree)


def reviewed(tree):
    """조립 결과를 본문 단계 없이 최종 검산(checks.review)에 넣어 경고를 받는다."""
    full = {**json.loads(json.dumps(tree)), "data": {**json.loads(json.dumps(tree["data"])), "announcement": {},
                                                     "schedules": []}}
    return checks.review(full, [1, 2, 3, 4, 5], ["", "", "", "", "Sample Complex 18A2 청년 ( 주거약자) 3"], [], 0)


def test_compact_supply_text_that_disagrees_with_source_cells_becomes_unresolved():
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[4],
        supply_row_ids=frozenset({"P4:T1:R4"}), supply_row_text={"P4:T1:R4": "Test\t26A\t청년\t20"},
        notice_text="body", notice_pages=[1], complex_candidates=["Test"], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={"P4:T1:R4": {"complexName": "Test", "housingType": "26A", "target": "청년",
                                         "reserveCount": "20"}},
    )
    compact = {"data": {"rows": [{"rowIds": ["P4:T1:R4"], "complexName": "Fake", "housingType": "26B",
                                   "target": "고령자", "reserveCount": 99}]}}

    tree, _ = supply_assembly.assemble(compact, selected)

    row = tree["data"]["supplyRows"][0]
    assert row["complexReference"]["sourceComplexName"] == ""
    assert row["sourceHousingTypeName"] is None
    assert row["targets"][0]["target"] == ""
    assert row["targets"][0]["reserveCount"] is None
    assert {item["path"] for item in tree["data"]["unresolvedFields"]} >= {
        "supplyRows[0].complexReference.sourceComplexName", "supplyRows[0].sourceHousingTypeName",
        "supplyRows[0].targets[0].target", "supplyRows[0].targets[0].reserveCount",
    }


def test_compact_shared_count_is_mapped_to_each_target_without_copying_the_value():
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[4],
        supply_row_ids=frozenset({"P4:T1:R4", "P4:T1:R5", "P4:T1:R6"}),
        supply_row_text={
            "P4:T1:R4": "Test\t26A\t청년", "P4:T1:R5": "Test\t26A\t고령자",
            "P4:T1:R6": "공통 예비입주자 모집수\t20",
        },
        notice_text="body", notice_pages=[1], complex_candidates=["Test"], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={
            "P4:T1:R4": {"complexName": "Test", "housingType": "26A", "target": "청년"},
            "P4:T1:R5": {"complexName": "Test", "housingType": "26A", "target": "고령자"},
            "P4:T1:R6": {"reserveCount": "20"},
        },
    )
    compact = {
        "data": {"rows": [
            {"rowIds": ["P4:T1:R4"], "complexName": "Test", "housingType": "26A", "target": "청년"},
            {"rowIds": ["P4:T1:R5"], "complexName": "Test", "housingType": "26A", "target": "고령자"},
        ]},
        "sharedCounts": [{"rowIndexes": [0, 1], "field": "reserveCount", "count": 20,
                          "rowIds": ["P4:T1:R6"]}],
    }

    tree, _ = supply_assembly.assemble(compact, selected)

    assert [target["reserveCount"] for target in tree["data"]["supplyRows"][0]["targets"]] == [None, None]
    assert tree["sharedCounts"] == [{
        "paths": ["supplyRows[0].targets[0].reserveCount", "supplyRows[0].targets[1].reserveCount"],
        "count": 20, "page": 4, "quote": "공통 예비입주자 모집수\t20",
    }]
    assert not any(item["path"].endswith("reserveCount") for item in tree["data"]["unresolvedFields"])


def test_model_target_with_income_split_column_is_accepted_as_the_combined_source_name():
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[8],
        supply_row_ids=frozenset({"P8:T1:R3"}), supply_row_text={"P8:T1:R3": "Test\t24A\t청년\t소득 없음\t46,920"},
        notice_text="body", notice_pages=[1], complex_candidates=["Test"], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={"P8:T1:R3": {"complexName": "Test", "housingType": "24A", "target": "청년",
                                         "targetDetail": "소득 없음"}},
    )
    compact = {"data": {"rows": [
        {"rowIds": ["P8:T1:R3"], "complexName": "Test", "housingType": "24A", "target": "청년(소득 없음)"},
        {"rowIds": ["P8:T1:R3"], "complexName": "Test", "housingType": "24A", "target": "청년(소득 있음)"},
    ]}}

    tree, _ = supply_assembly.assemble(compact, selected)

    assert [target["target"] for target in tree["data"]["supplyRows"][0]["targets"]] == ["청년(소득 없음)", ""]
    assert "supplyRows[0].targets[1].target" in {item["path"] for item in tree["data"]["unresolvedFields"]}


def test_each_value_gets_evidence_from_the_row_that_holds_its_cell():
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[4, 13],
        supply_row_ids=frozenset({"P4:T1:R4", "P13:T1:R3"}),
        supply_row_text={"P4:T1:R4": "Test\t26A\t청년\t20\t26.73", "P13:T1:R3": "Test\t26A\t청년\t46,920\t178,670"},
        notice_text="body", notice_pages=[1], complex_candidates=["Test"], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={
            "P4:T1:R4": {"complexName": "Test", "housingType": "26A", "target": "청년", "reserveCount": "20",
                          "exclusiveArea": "26.73"},
            "P13:T1:R3": {"complexName": "Test", "housingType": "26A", "target": "청년", "rentalDeposit": "46,920",
                           "rentalDepositUnit": "천원", "monthlyRent": "178,670", "monthlyRentUnit": "원"},
        },
    )
    compact = {"data": {"rows": [{
        "rowIds": ["P4:T1:R4", "P13:T1:R3"], "complexName": "Test", "housingType": "26A", "target": "청년",
        "reserveCount": 20, "exclusiveArea": 26.73, "rentalDeposit": 46920000, "monthlyRent": 178670,
    }]}}

    tree, _ = supply_assembly.assemble(compact, selected)

    pages_by_path = {item["path"]: item["page"] for item in tree["evidence"]}
    # 공급행·대상 근거가 두 쪽에 걸쳐도 값마다 그 칸이 있는 행의 쪽을 가리킨다(13쪽이 4쪽보다 글자순으로 앞서도).
    assert pages_by_path["supplyRows[0].exclusiveArea"] == 4
    assert pages_by_path["supplyRows[0].targets[0].reserveCount"] == 4
    assert pages_by_path["supplyRows[0].targets[0].rentalDeposit"] == 13
    assert pages_by_path["supplyRows[0].targets[0].monthlyRent"] == 13


def test_value_evidence_prefers_the_row_whose_cell_matches_the_value():
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[8, 9],
        supply_row_ids=frozenset({"P8:T1:R3", "P9:T1:R3"}),
        supply_row_text={"P8:T1:R3": "Test\t26A\t청년\t소득 없음\t46,920", "P9:T1:R3": "\t\t\t소득 있음\t49,680"},
        notice_text="body", notice_pages=[1], complex_candidates=["Test"], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={
            "P8:T1:R3": {"complexName": "Test", "housingType": "26A", "target": "청년", "targetDetail": "소득 없음",
                          "rentalDeposit": "46,920", "rentalDepositUnit": "천원"},
            "P9:T1:R3": {"targetDetail": "소득 있음", "rentalDeposit": "49,680", "rentalDepositUnit": "천원"},
        },
    )
    compact = {"data": {"rows": [{"rowIds": ["P8:T1:R3", "P9:T1:R3"], "complexName": "Test", "housingType": "26A",
                                   "target": "청년(소득 있음)", "rentalDeposit": 49680000}]}}

    tree, _ = supply_assembly.assemble(compact, selected)

    evidence = next(item for item in tree["evidence"] if item["path"] == "supplyRows[0].targets[0].rentalDeposit")
    assert evidence["page"] == 9 and "49,680" in evidence["quote"]


# 같은 원문 셀에서 검증되지 않은 모델 수치를 확정하지 않는다.
def extract(rows, item):
    table_rows = tables.SupplyTable.of(1, 1, rows, None, False).rows[1:]
    ids = [row.id for row in table_rows]
    selected = pages.PromptPages(
        rental_rows=0, supply_text='', supply_pages=[1], supply_row_ids=frozenset(ids),
        supply_row_text={row.id: row.text for row in table_rows},
        supply_row_values={row.id: row.values for row in table_rows},
        notice_text='', notice_pages=[], complex_candidates=[], table_structure_fallback=False,
        ambiguous_supply_tables=False,
    )
    tree, _ = supply_assembly.assemble({'data': {'rows': [{
        'rowIds': ids, 'complexName': 'Test', 'housingType': rows[1][1], 'target': '청년', **item,
    }]}}, selected)
    return tree


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


@pytest.mark.parametrize("cell, expected", [
    ("서울특별시 중구 세종대로 110 (태평로1가)", "서울특별시 중구 세종대로 110"),
    ("강원특별자치도 평창군 용평면 장평리 123-4번지 일원", "강원특별자치도 평창군 용평면 장평리 123-4"),
    ("경기도 화성시 동탄면 영천리 12, 13 외 3필지", "경기도 화성시 동탄면 영천리 12"),
    ("경기도\n화성시  동탄대로 5", "경기도 화성시 동탄대로 5"),
    ("화성동탄2 A-3블록", None),
    ("경기도 화성시 일원", None),
    (None, None),
])
def test_supply_table_address_cell_becomes_one_representative_address(cell, expected):
    assert supply_assembly.normalize_address(cell) == expected


@pytest.mark.parametrize("left, right", [
    ("서울시 중구 세종대로 110", "서울특별시 중구 세종대로 110"),
    ("강원도 평창군 용평면 새이들길 23", "강원특별자치도 평창군 용평면 새이들길 23"),
    ("전라북도 전주시 완산구 효자로 1", "전북특별자치도 전주시 완산구 효자로 1"),
])
def test_abbreviated_or_old_province_names_are_the_same_address(left, right):
    assert supply_assembly.address_key(left) == supply_assembly.address_key(right)


def test_jeju_city_address_is_not_read_as_a_province_abbreviation():
    assert supply_assembly.address_key("제주시 연동 1") != supply_assembly.address_key("제주특별자치도 연동 1")


def test_address_cut_off_inside_parentheses_by_the_cell_width_keeps_the_address_before_it():
    assert supply_assembly.normalize_address("서울특별시 중구 세종대로 110(태평로1가") == "서울특별시 중구 세종대로 110"


@pytest.mark.parametrize("cell", ["경기도 화성시 동탄2신도시 일원", "경기도 화성시 동탄2지구 A-62블록"])
def test_district_or_block_names_without_a_lot_or_building_number_are_not_addresses(cell):
    assert supply_assembly.normalize_address(cell) is None
