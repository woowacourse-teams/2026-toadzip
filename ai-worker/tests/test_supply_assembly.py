"""공급 단계 모델의 답과 쪽 표가 공급표(data.supplySheet)가 되는 규칙. 모델 답은 공급행 목록 모양으로 적고 sheet_answer가 공급표
모양 답으로 바꾼다. 결과는 키 대신 '주택형/대상' 이름(readable)으로 읽는다."""

import dataclasses

import pytest

from ai_worker import checks, pages, supply_assembly, supply_grouping, tables
from ai_worker.source_frame import SourceSupplyRow
from conftest import evidence_at, model_stage, model_supply, readable, sheet_answer


def supplied(selected, *rows, shared=(), source_names=()):
    return model_supply(selected, rows=rows, shared=shared, source_names=source_names)


def test_model_rows_are_checked_against_cell_values_and_become_one_housing_type_with_target_cells():
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

    result = supplied(selected,
                      {"rowIds": ["P4:T1:R4"], "complexName": "Test", "housingType": "26A", "target": "청년",
                       "reserveCount": 20, "exclusiveArea": 26, "commonArea": 15},
                      {"rowIds": ["P4:T1:R5"], "complexName": "Test", "housingType": "26A", "target": "고령자",
                       "reserveCount": 3})

    [housing_type] = result.sheet["housingTypes"]
    assert (housing_type["complexName"], housing_type["name"], housing_type["exclusiveArea"],
            housing_type["commonArea"]) == ("Test", "26A", 26, 15)
    assert readable(result.sheet) == {"cells": ["26A/청년", "26A/고령자"],
                                      "quotas": [("RESERVE", 20, ["26A/청년"]), ("RESERVE", 3, ["26A/고령자"])],
                                      "leaseTerms": []}
    assert result.tree["data"]["complexes"] == [{
        "sourceComplexName": "Test", "address": None, "totalHouseholdCount": 100, "parkingCount": None,
    }]
    assert "complexes[0]" in {item["path"] for item in result.tree["evidence"]}
    assert {item["path"] for item in result.sheet["evidence"]} >= {"housingTypes[0]", "cells[0]", "cells[1]"}
    assert result.unresolved() == set()


SEONUN_RENT_CELLS = [
    ["공급형별", "공급대상", "대기중인 예비자수", "금회모집 예비자수", "임대보증금(천원)", "월임대료(원)",
     "전환가능 보증금 한도액(천원)", None],
    ["55A-1 (주거약자)", "신혼부부· 한부모가족", "-", "20", "67,400", "342,610", "(+)", "41,000"],
    ["55B-1 (주거약자)", None, "-", "20", None, None, "(-)", "55,000"],
]


def seonun_pages(headings: dict[str, str] | None = None) -> pages.PromptPages:
    """광주선운2 A-1블록 임대조건표(대상·보증금·월세 칸이 두 형에 걸쳐 병합)를 표 판독기로 읽은 입력."""
    rows = tables.SupplyTable.of(4, 2, SEONUN_RENT_CELLS, None, False).rows
    return pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[4],
        supply_row_ids=frozenset(row.id for row in rows), supply_row_text={row.id: row.text for row in rows},
        notice_text="body", notice_pages=[1], complex_candidates=[], table_structure_fallback=False,
        ambiguous_supply_tables=False, supply_row_values={row.id: row.values for row in rows},
        supply_row_headings=headings or {})


def test_lower_housing_type_under_merged_target_and_rent_cells_keeps_its_target_and_amounts():
    # 병합 칸 아래 형(55B-1)의 대상·금액을 원문 대조가 지우면 대상이 '전체'가 되고 보증금·월세가 빈다.
    # 수량 칸은 형마다 따로 적혀 있어 형마다 자기 수(20)다.
    item = {"complexName": "", "target": "신혼부부· 한부모가족", "reserveCount": 20, "rentalDeposit": 67400000,
            "monthlyRent": 342610, "convertedDeposit": 41000000}

    result = supplied(seonun_pages(), {**item, "rowIds": ["P4:T2:R2"], "housingType": "55A-1 (주거약자)"},
                      {**item, "rowIds": ["P4:T2:R3"], "housingType": "55B-1 (주거약자)"})

    a, b = "55A-1 (주거약자)/신혼부부·한부모가족", "55B-1 (주거약자)/신혼부부·한부모가족"
    assert readable(result.sheet) == {"cells": [a, b], "quotas": [("RESERVE", 20, [a]), ("RESERVE", 20, [b])],
                                      "leaseTerms": [(67400000, 342610, [a]), (67400000, 342610, [b])]}
    assert [lease["convertedDeposit"] for lease in result.sheet["leaseTerms"]] == [41000000, 41000000]
    assert not [path for path in result.unresolved() if not path.endswith(".complexName")]


def test_complex_names_from_block_headings_keep_same_type_rows_of_two_blocks_apart():
    # 표에 단지명 열이 없고 블록 제목이 표 위에 있다. 단지명을 지우면 두 블록의 55B-1이 한 주택형으로 접힌다.
    above_a1 = "■ 광주선운2 A-1블록 (총 336세대)\n[금회모집 임대조건]"
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[4],
        supply_row_ids=frozenset({"P4:T2:R4", "P4:T4:R4"}),
        supply_row_text={"P4:T2:R4": "55B-1\t신혼부부· 한부모가족\t-\t20", "P4:T4:R4": "55B-1\t신혼부부· 한부모가족\t-\t20"},
        notice_text="body", notice_pages=[1], complex_candidates=[], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={row_id: {"housingType": "55B-1", "target": "신혼부부· 한부모가족", "reserveCount": "20"}
                           for row_id in ("P4:T2:R4", "P4:T4:R4")},
        supply_row_headings={"P4:T2:R4": above_a1,
                             "P4:T4:R4": f"{above_a1}\n55B-1\n■ 광주선운2 A-3블록 (총 147세대)"},
    )

    def rows(a3_name):
        return [{"rowIds": [row_id], "complexName": name, "housingType": "55B-1", "target": "신혼부부· 한부모가족",
                 "reserveCount": 20}
                for row_id, name in (("P4:T2:R4", "광주선운2 A-1블록"), ("P4:T4:R4", a3_name))]

    result = supplied(selected, *rows("광주선운2 A-3블록"))

    assert [(item["complexName"], item["name"]) for item in result.sheet["housingTypes"]] == [
        ("광주선운2 A-1블록", "55B-1"), ("광주선운2 A-3블록", "55B-1")]
    assert [item["sourceComplexName"] for item in result.tree["data"]["complexes"]] == ["광주선운2 A-1블록", "광주선운2 A-3블록"]
    assert [count for _, count, _ in readable(result.sheet)["quotas"]] == [20, 20]
    assert result.unresolved() == set()

    # 모델이 두 블록 행을 모두 A-1블록으로 내도 원천(LH) 단지명에 A-3블록이 있으면 A-3블록 표 행의 A-1블록은 확인되지 않는다.
    mislabeled = supplied(selected, *rows("광주선운2 A-1블록"), source_names=["광주선운2 A-1블록", "광주선운2 A-3블록"])

    # 같은 이름·같은 형이라 한 주택형으로 묶이고(기존 동작), 단지명은 미확인으로 남아 화면이 확인을 요구한다.
    assert [(item["complexName"], item["name"]) for item in mislabeled.sheet["housingTypes"]] == [
        ("광주선운2 A-1블록", "55B-1")]
    assert "housingTypes[0].complexName" in mislabeled.unresolved()


def test_target_removes_extraction_whitespace_around_parentheses():
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

    result = supplied(selected, {"rowIds": ["P5:T1:R38"], "complexName": "Sample Complex", "housingType": "18A2",
                                 "target": "청년 ( 주거약자)", "reserveCount": 3})

    assert [target["label"] for target in result.sheet["targets"]] == ["청년(주거약자)"]
    assert result.unresolved() == set()


def test_supply_text_that_disagrees_with_source_cells_becomes_unresolved():
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[4],
        supply_row_ids=frozenset({"P4:T1:R4"}), supply_row_text={"P4:T1:R4": "Test\t26A\t청년\t20"},
        notice_text="body", notice_pages=[1], complex_candidates=["Test"], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={"P4:T1:R4": {"complexName": "Test", "housingType": "26A", "target": "청년",
                                         "reserveCount": "20"}},
    )

    result = supplied(selected, {"rowIds": ["P4:T1:R4"], "complexName": "Fake", "housingType": "26B",
                                 "target": "고령자", "reserveCount": 99})

    [housing_type] = result.sheet["housingTypes"]
    assert (housing_type["complexName"], housing_type["name"]) == ("", None)
    assert [target["label"] for target in result.sheet["targets"]] == [""]
    assert result.sheet["quotas"] == []
    assert result.unresolved() >= {"housingTypes[0].complexName", "housingTypes[0].name", "targets[0].label",
                                   "cells[0].reserve"}


def test_shared_count_is_one_quota_of_both_targets_without_copying_the_value():
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

    result = supplied(selected,
                      {"rowIds": ["P4:T1:R4"], "complexName": "Test", "housingType": "26A", "target": "청년"},
                      {"rowIds": ["P4:T1:R5"], "complexName": "Test", "housingType": "26A", "target": "고령자"},
                      shared=[{"rowIndexes": [0, 1], "field": "reserveCount", "count": 20, "rowIds": ["P4:T1:R6"]}])

    assert readable(result.sheet)["quotas"] == [("RESERVE", 20, ["26A/청년", "26A/고령자"])]
    assert evidence_at(result.sheet, "quotas[0]") == {"path": "quotas[0]", "page": 4, "quote": "공통 예비입주자 모집수\t20"}
    assert not [path for path in result.unresolved() if path.endswith(".reserve")]


def test_model_target_with_income_split_column_is_accepted_as_the_combined_source_name():
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[8],
        supply_row_ids=frozenset({"P8:T1:R3"}), supply_row_text={"P8:T1:R3": "Test\t24A\t청년\t소득 없음\t46,920"},
        notice_text="body", notice_pages=[1], complex_candidates=["Test"], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={"P8:T1:R3": {"complexName": "Test", "housingType": "24A", "target": "청년",
                                         "targetDetail": "소득 없음"}},
    )

    result = supplied(selected,
                      {"rowIds": ["P8:T1:R3"], "complexName": "Test", "housingType": "24A", "target": "청년(소득 없음)"},
                      {"rowIds": ["P8:T1:R3"], "complexName": "Test", "housingType": "24A", "target": "청년(소득 있음)"})

    assert [target["label"] for target in result.sheet["targets"]] == ["청년(소득 없음)", ""]
    assert "targets[1].label" in result.unresolved()


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

    result = supplied(selected, {
        "rowIds": ["P4:T1:R4", "P13:T1:R3"], "complexName": "Test", "housingType": "26A", "target": "청년",
        "reserveCount": 20, "exclusiveArea": 26.73, "rentalDeposit": 46920000, "monthlyRent": 178670,
    })

    # 주택형·칸 근거가 두 쪽에 걸쳐도 값마다 그 칸이 있는 행의 쪽을 가리킨다(13쪽이 4쪽보다 글자순으로 앞서도).
    assert readable(result.sheet)["quotas"] == [("RESERVE", 20, ["26A/청년"])]
    assert evidence_at(result.sheet, "housingTypes[0].exclusiveArea")["page"] == 4
    assert evidence_at(result.sheet, "quotas[0].count")["page"] == 4
    assert evidence_at(result.sheet, "leaseTerms[0].rentalDeposit")["page"] == 13
    assert evidence_at(result.sheet, "leaseTerms[0].monthlyRent")["page"] == 13


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

    result = supplied(selected, {"rowIds": ["P8:T1:R3", "P9:T1:R3"], "complexName": "Test", "housingType": "26A",
                                 "target": "청년(소득 있음)", "rentalDeposit": 49680000})

    evidence = evidence_at(result.sheet, "leaseTerms[0].rentalDeposit")
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
    return supplied(selected, {'rowIds': ids, 'complexName': 'Test', 'housingType': rows[1][1], 'target': '청년', **item})


@pytest.mark.parametrize('header', ['잔여예비자', '기존예비자', '현재예비자', '대기예비자'])
def test_existing_waitlist_is_not_new_reserve_recruitment(header):
    result = extract([['단지명', '주택형', '공급대상', header], ['Test', '26A', '청년', '20']], {'reserveCount': 20})
    assert result.sheet['quotas'] == []
    assert 'cells[0].reserve' in result.unresolved()


@pytest.mark.parametrize(('header', 'value', 'expected'), [
    ('임대보증금(원)', 46920, 46920), ('임대보증금(원)', 46920000, None),
    ('임대보증금(천원)', 46920000, 46920000), ('임대보증금(천원)', 46920, None),
    ('임대보증금', 46920000, None),
])
def test_money_validation_obeys_explicit_source_unit(header, value, expected):
    result = extract([['단지명', '주택형', '공급대상', header], ['Test', '26A', '청년', '46,920']], {'rentalDeposit': value})
    assert [lease['rentalDeposit'] for lease in result.sheet['leaseTerms']] == ([expected] if expected else [])
    assert ('cells[0].rentalDeposit' in result.unresolved()) == (expected is None)


@pytest.mark.parametrize(('source_type', 'area', 'expected'), [('26A/B', 999.99, None), ('26A', 999.99, None), ('26A', 26.37, 26.37)])
def test_subtype_area_requires_matching_subtype_and_column(source_type, area, expected):
    result = extract([
        ['단지명', '주택형', '공급대상', '전용면적', '주거공용', '건설호수'],
        ['Test', '26A/B', '청년', '', '', ''],
        ['Test', source_type, '청년', '26.37', '15.1', '10'],
    ], {'subtypes': [{'name': '26A', 'exclusiveArea': area, 'commonArea': 888, 'constructedHouseholdCount': 9999}]})
    [house_type] = result.sheet['housingTypes'][0]['houseTypes']
    assert house_type['exclusiveArea'] == expected
    assert house_type['commonArea'] is None
    assert house_type['constructedHouseholdCount'] is None
    assert 'housingTypes[0].houseTypes[0].commonArea' in result.unresolved()


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


def test_model_repeating_the_same_table_row_keeps_one_cell():
    # 사천벌리1: 모델이 같은 표 행을 두 번 쓰면(대상 이름 없음·예비 110) 같은 대상이 둘 생겨 예비자 수가 두 번 보였다.
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[1],
        supply_row_ids=frozenset({"P1:T1:R3"}),
        supply_row_text={"P1:T1:R3": "26\t298\t26.37\t10.76\t-\t-\t37.13\t77\t110"},
        notice_text="body", notice_pages=[1], complex_candidates=[], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={"P1:T1:R3": {"housingType": "26", "exclusiveArea": "26.37", "reserveCount": "110"}},
    )
    item = {"rowIds": ["P1:T1:R3"], "complexName": "", "housingType": "26", "target": "", "reserveCount": 110,
            "exclusiveArea": 26.37}

    result = supplied(selected, item, dict(item))

    assert readable(result.sheet) == {"cells": ["26/전체"], "quotas": [("RESERVE", 110, ["26/전체"])], "leaseTerms": []}
    assert not [path for path in result.unresolved() if "cells[" in path or "targets[" in path]


def sacheon_pages() -> pages.PromptPages:
    """사천벌리1 공급표(1쪽)와 신청자 묶음 머리글로 나뉜 임대조건표(2쪽)를 표 판독기로 읽은 입력."""
    from test_tables import SACHEON_RENT_CELLS, SACHEON_SUPPLY_CELLS
    rows = [*tables.SupplyTable.of(1, 1, SACHEON_SUPPLY_CELLS, None, False).rows,
            *tables.SupplyTable.of(2, 1, SACHEON_RENT_CELLS, None, False).rows]
    return pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[1, 2],
        supply_row_ids=frozenset(row.id for row in rows), supply_row_text={row.id: row.text for row in rows},
        notice_text="body", notice_pages=[1], complex_candidates=[], table_structure_fallback=False,
        ambiguous_supply_tables=False, supply_row_values={row.id: row.values for row in rows},
        supply_row_target_values={row.id: row.target_values for row in rows},
    )


def sacheon_item(row: int, target: str, deposit: int, rent: int) -> dict:
    return {"rowIds": [f"P1:T1:R{row}", f"P2:T1:R{row}"], "complexName": "", "housingType": {4: "26", 5: "31"}[row],
            "target": target, "reserveCount": {4: 110, 5: 30}[row], "rentalDeposit": deposit, "monthlyRent": rent}


def test_targets_named_by_applicant_group_headers_are_checked_against_that_groups_columns():
    # 사천벌리1 임대조건표는 가군·나군이 금액 열 위의 병합 머리글이다. 대상 이름은 칸이 아니라 그 머리글에 있고,
    # 보증금·월임대료 열은 묶음마다 하나씩이라 표 전체로는 정할 수 없어 맞는 응답도 모두 비워졌다.
    result = supplied(sacheon_pages(),
                      sacheon_item(4, "가군", 2520000, 50160), sacheon_item(4, "나군", 7648000, 115940),
                      sacheon_item(5, "가군", 2993000, 59570), sacheon_item(5, "나군", 8692000, 127970),
                      shared=[{"rowIndexes": [0, 1], "field": "reserveCount", "count": 110, "rowIds": ["P1:T1:R4"]}])

    # 예비자 칸 하나를 가군·나군이 함께 쓰므로 대상마다 복제하지 않고 두 칸이 함께 쓰는 수량 하나로 둔다.
    assert readable(result.sheet) == {
        "cells": ["26/가군", "26/나군", "31/가군", "31/나군"],
        "quotas": [("RESERVE", 110, ["26/가군", "26/나군"]), ("RESERVE", 30, ["31/가군", "31/나군"])],
        "leaseTerms": [(2520000, 50160, ["26/가군"]), (7648000, 115940, ["26/나군"]),
                       (2993000, 59570, ["31/가군"]), (8692000, 127970, ["31/나군"])],
    }
    assert not [path for path in result.unresolved() if not path.endswith(".complexName")]
    # 금액 근거는 공급표(1쪽)가 아니라 그 금액 칸이 있는 임대조건표(2쪽) 행을 가리킨다.
    deposit = evidence_at(result.sheet, "leaseTerms[1].rentalDeposit")
    assert deposit["page"] == 2 and "7,648,000" in deposit["quote"]


@pytest.mark.parametrize(("target", "deposit", "rent"), [
    ("나군", 2520000, 50160),  # 다른 묶음(가군) 열의 금액
    ("가군", 126000, 50160),  # 계가 아닌 계약금 열
    ("다군", 2520000, 50160),  # 머리글에 없는 묶음
])
def test_amounts_outside_the_named_groups_columns_are_not_confirmed(target, deposit, rent):
    result = supplied(sacheon_pages(), sacheon_item(4, target, deposit, rent))

    assert [lease["rentalDeposit"] for lease in result.sheet["leaseTerms"]] in ([], [None])
    assert result.unresolved() & {"cells[0].rentalDeposit", "leaseTerms[0].rentalDeposit"}
    assert [item["label"] for item in result.sheet["targets"]] == ["" if target == "다군" else target]


def standard_and_lump_sum_supply(deposit: int, rent: int):
    """30번 공고 모양의 모델 답. 공급대상 열이 없는 임대조건표의 한 행에서 기본 임대조건을 읽었다."""
    from test_tables import STANDARD_AND_LUMP_SUM_CELLS
    answer = {"data": {
        "housingTypes": [{"key": "h1", "complexName": "경암부향", "name": "39", "rowIds": ["P4:T1:R4"], "targets": ["t1"]}],
        "targets": [{"key": "t1", "label": "일반"}], "quotas": [],
        "leaseTerms": [{"members": [["h1", "t1"]], "rentalDeposit": deposit, "monthlyRent": rent, "rowIds": ["P4:T1:R4"]}]}}
    return model_supply(table_pages(STANDARD_AND_LUMP_SUM_CELLS, page=4), answer)


def test_base_rent_beside_a_lump_sum_deposit_option_stays_after_the_source_check():
    # 일반형과 전세형 임대조건이 나란한 표에서 모델이 맞게 읽은 일반형 금액이 원문 대조에서 모두 비워졌다.
    result = standard_and_lump_sum_supply(9330000, 82630)

    assert readable(result.sheet)["leaseTerms"] == [(9330000, 82630, ["39/전체"])]
    assert not {path for path in result.unresolved() if path.endswith((".rentalDeposit", ".monthlyRent"))}


@pytest.mark.parametrize(("deposit", "rent", "wrong"), [
    (25856000, 82630, "rentalDeposit"),  # 전세형 보증금
    (300000, 82630, "rentalDeposit"),  # 계가 아닌 계약금 열
    (9330000, 62630, "monthlyRent"),  # 최대전환 시 월임대료
])
def test_amounts_of_the_lump_sum_or_conversion_columns_are_not_the_base_rent(deposit, rent, wrong):
    result = standard_and_lump_sum_supply(deposit, rent)

    kept = {"rentalDeposit": 9330000, "monthlyRent": 82630} | {wrong: None}
    assert [(lease["rentalDeposit"], lease["monthlyRent"]) for lease in result.sheet["leaseTerms"]] == [
        (kept["rentalDeposit"], kept["monthlyRent"])]
    assert f"leaseTerms[0].{wrong}" in result.unresolved()


@pytest.mark.parametrize(("source", "expected"), [
    ("청년(소득無)", "청년(소득 없음)"),
    ("청년(소득有)", "청년(소득 있음)"),
    ("청년 소득(×)", "청년(소득 없음)"),
    ("청년소득(○)", "청년(소득 있음)"),
    ("청년(소득 X)", "청년(소득 없음)"),
    ("청년(소득O)", "청년(소득 있음)"),
    ("청년(소득없음)", "청년(소득 없음)"),
    ("청년 (소득 있음)", "청년(소득 있음)"),
    ("청년(소득 무)", "청년(소득 없음)"),
    ("청년(소득 유)", "청년(소득 있음)"),
    ("소득 無", "소득 없음"),
    ("고령자(주거약자용)", "고령자(주거약자용)"),
    ("소득유무 무관", "소득유무 무관"),
    ("신혼부부· 한부모가족", "신혼부부·한부모가족"),
    ("신혼부부․ 한부모가족", "신혼부부·한부모가족"),
    ("신혼부부ㆍ한부모가족", "신혼부부·한부모가족"),
    ("「가」군", "가군"),
])
def test_income_split_targets_use_one_notation(source, expected):
    # 같은 공급대상을 소득 유무로 나눈 표기는 공고마다 다르다(소득無·소득(×)·소득 X). 단지에 쌓이는 대상 이름을 하나로 맞춘다.
    assert supply_assembly.canonical_target(source) == expected


def test_single_target_of_a_table_without_target_column_is_all_applicants():
    # 화성동탄2 리츠처럼 공급표·임대조건표에 공급대상 열이 없으면 신청자 구분이 없는 행이다. 모델이 지어낸 이름(일반) 대신
    # LH 정제와 같은 '전체'로 둔다. 대상 열이 있는데 확인하지 못한 이름은 그대로 비운다.
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[4],
        supply_row_ids=frozenset({"P4:T2:R5", "P5:T1:R4"}),
        supply_row_text={"P4:T2:R5": "동탄2 A40BL\t074.9900A\t74.99\t272\t2", "P5:T1:R4": "동탄2 A40BL\t74A\t70,163,000\t701,630"},
        notice_text="body", notice_pages=[1], complex_candidates=[], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={"P4:T2:R5": {"complexName": "동탄2 A40BL", "housingType": "074.9900A", "exclusiveArea": "74.99"},
                           "P5:T1:R4": {"rentalDeposit": "70,163,000", "monthlyRent": "701,630"}},
    )
    item = {"rowIds": ["P4:T2:R5", "P5:T1:R4"], "complexName": "동탄2 A40BL", "housingType": "074.9900A", "target": "일반",
            "exclusiveArea": 74.99, "rentalDeposit": 70163000, "monthlyRent": 701630}

    result = supplied(selected, item)

    assert [item["complexName"] for item in result.sheet["housingTypes"]] == ["동탄2 A40BL"]
    assert readable(result.sheet)["leaseTerms"] == [(70163000, 701630, ["074.9900A/전체"])]
    assert result.unresolved() == set()

    with_column = {key: {**value, "target": "청년"} if key == "P4:T2:R5" else value
                   for key, value in selected.supply_row_values.items()}
    result = supplied(dataclasses.replace(selected, supply_row_values=with_column), item)
    assert [target["label"] for target in result.sheet["targets"]] == [""]
    assert "targets[0].label" in result.unresolved()


def area(selected, item):
    return supplied(selected, item).sheet["housingTypes"][0]["exclusiveArea"]


def test_exclusive_area_missing_from_the_model_is_read_from_the_source_cell_of_that_row():
    # 부산문현2: 모델이 주택형마다 전용면적을 빠뜨린 실행이 있었다. 원문 표 칸(39.85)이 하나로 정해지면 그 값을 쓴다.
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[3],
        supply_row_ids=frozenset({"P3:T1:R4"}), supply_row_text={"P3:T1:R4": "39\t44\t39.85\t15.5285"},
        notice_text="body", notice_pages=[1], complex_candidates=[], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={"P3:T1:R4": {"housingType": "39", "exclusiveArea": "39.85", "constructedHouseholdCount": "44"}},
    )

    assert area(selected, {"rowIds": ["P3:T1:R4"], "complexName": "", "housingType": "39", "target": "대학생"}) == 39.85


def test_exclusive_area_of_a_row_pointing_only_at_the_rent_table_comes_from_the_supply_row_of_the_same_type():
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[3, 4],
        supply_row_ids=frozenset({"P3:T1:R4", "P4:T1:R5"}),
        supply_row_text={"P3:T1:R4": "39\t44\t39.85", "P4:T1:R5": "39\t대학생\t56,780,000"},
        notice_text="body", notice_pages=[1], complex_candidates=[], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={"P3:T1:R4": {"housingType": "39", "exclusiveArea": "39.85"},
                           "P4:T1:R5": {"housingType": "39", "target": "대학생", "rentalDeposit": "56,780,000"}},
    )

    assert area(selected, {"rowIds": ["P4:T1:R5"], "complexName": "", "housingType": "39", "target": "대학생"}) == 39.85


def test_subtype_areas_written_with_labels_in_one_cell_are_read_and_subtypes_take_the_type_number():
    # 인천부평: 공급표 전용면적 칸이 "(A)36.02 (B)36.19"이고 모델은 세부 주택형을 A·B로만 적었다.
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[3],
        supply_row_ids=frozenset({"P3:T1:R14"}), supply_row_text={"P3:T1:R14": "36 A/B\t신혼부부·한부모가족\t(A)36.02 (B)36.19"},
        notice_text="body", notice_pages=[1], complex_candidates=[], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={"P3:T1:R14": {"housingType": "36 A/B", "target": "신혼부부·한부모가족",
                                         "exclusiveArea": "(A)36.02 (B)36.19"}},
    )

    result = supplied(selected, {"rowIds": ["P3:T1:R14"], "complexName": "", "housingType": "36 A/B",
                                 "target": "신혼부부·한부모가족",
                                 "subtypes": [{"name": "A"}, {"name": "B", "exclusiveArea": 36.19}]})

    assert [(item["name"], item["exclusiveArea"]) for item in result.sheet["housingTypes"][0]["houseTypes"]] == [
        ("36A", 36.02), ("36B", 36.19)]


def test_area_is_not_taken_from_the_same_type_row_of_another_complex():
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[3, 4],
        supply_row_ids=frozenset({"P3:T1:R4", "P4:T1:R5"}),
        supply_row_text={"P3:T1:R4": "A단지\t39\t39.85", "P4:T1:R5": "B단지\t39\t대학생"},
        notice_text="body", notice_pages=[1], complex_candidates=[], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={"P3:T1:R4": {"complexName": "A단지", "housingType": "39", "exclusiveArea": "39.85"},
                           "P4:T1:R5": {"complexName": "B단지", "housingType": "39", "target": "대학생"}},
    )

    assert area(selected, {"rowIds": ["P4:T1:R5"], "complexName": "B단지", "housingType": "39", "target": "대학생"}) is None


def test_unnamed_targets_of_different_source_rows_of_one_type_are_one_cell_with_the_summed_count():
    # 같은 주택형의 두 표 행(101동·102동)을 모델이 대상 이름 없이 냈다. 공급표에서는 그 주택형의 대상 칸 하나에 수를 더한다.
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[1],
        supply_row_ids=frozenset({"P1:T1:R3", "P1:T1:R4"}),
        supply_row_text={"P1:T1:R3": "26\t101동\t5", "P1:T1:R4": "26\t102동\t5"},
        notice_text="body", notice_pages=[1], complex_candidates=[], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={"P1:T1:R3": {"housingType": "26", "reserveCount": "5"},
                           "P1:T1:R4": {"housingType": "26", "reserveCount": "5"}},
    )

    result = supplied(selected, {"rowIds": ["P1:T1:R3"], "housingType": "26", "target": "", "reserveCount": 5},
                      {"rowIds": ["P1:T1:R4"], "housingType": "26", "target": "", "reserveCount": 5})

    assert readable(result.sheet) == {"cells": ["26/"], "quotas": [("RESERVE", 10, ["26/"])], "leaseTerms": []}


def test_area_is_not_taken_from_a_cited_row_of_another_housing_type():
    # 경기행복주택 파주: 모델이 단지명을 이어받으려고 26A 행을 함께 가리켜도 35AB의 면적(병합 칸)으로 26.79를 쓰지 않는다.
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[4],
        supply_row_ids=frozenset({"P4:T1:R20", "P4:T1:R22"}),
        supply_row_text={"P4:T1:R20": "파주\t26A\t청년\t10\t26.7900", "P4:T1:R22": "35AB\t고령자\t10"},
        notice_text="body", notice_pages=[1], complex_candidates=[], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={"P4:T1:R20": {"complexName": "파주", "housingType": "26A", "exclusiveArea": "26.7900"},
                           "P4:T1:R22": {"housingType": "35AB", "target": "고령자", "reserveCount": "10"}},
    )

    assert area(selected, {"rowIds": ["P4:T1:R20", "P4:T1:R22"], "complexName": "파주", "housingType": "35AB",
                           "target": "고령자"}) is None


HOUSE_TYPE_TABLE = [
    ["주택형 (신청형)", "주택 타입", "주거 전용", "주거 공용", "금회 예비입주자 모집 호수"],
    ["049.7600A", "49A", "49.76", "17.8623", "35"],
    ["059.9000A", "59A", "59.98", "21.5309", "125"],
    [None, "59A1", None, None, None],
    [None, None, "59.99", "21.5345", None],
    [None, "59A2", None, None, None],
    ["059.9000B", "59B", "59.93", "21.5130", "25"],
    [None, "59B1", "59.92", "21.5094", None],
]


def house_type_pages():
    rows = tables.SupplyTable.of(3, 1, HOUSE_TYPE_TABLE, None, False).rows
    return pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[3], supply_row_ids=frozenset(row.id for row in rows),
        supply_row_text={row.id: row.text for row in rows}, notice_text="body", notice_pages=[1],
        complex_candidates=[], table_structure_fallback=False, ambiguous_supply_tables=False,
        supply_row_values={row.id: row.values for row in rows},
        supply_row_subtypes={row.id: row.subtypes for row in rows if row.subtypes},
    )


def one_type(selected, **item):
    result = supplied(selected, {"complexName": "", "target": "일반", **item})
    return result.sheet["housingTypes"][0], result.unresolved()


def test_house_types_of_a_housing_type_row_come_from_its_house_type_column_instead_of_the_model():
    # 모델은 신청형 행의 첫 면적만 읽고 세부 타입을 신청형 이름으로 냈다. 표의 주택타입 열이 세부 타입을 정한다.
    housing_type, unresolved = one_type(house_type_pages(), rowIds=["P3:T1:R3", "P3:T1:R4"], housingType="059.9000A",
                                        exclusiveArea=59.98, subtypes=[{"name": "059.9000A", "exclusiveArea": 59.98}])

    # 면적이 같은 59A·59A1도 공고문 이름대로 따로 둔다.
    assert housing_type["houseTypes"] == [
        {"name": name, "exclusiveArea": area, "commonArea": common, "supplyArea": None, "constructedHouseholdCount": None}
        for name, area, common in (("59A", 59.98, 21.5309), ("59A1", 59.98, 21.5309), ("59A2", 59.99, 21.5345))
    ]
    # 주택형 자체의 면적은 모델이 낸 첫 면적을 원문 칸과 대조한 값이다.
    assert housing_type["exclusiveArea"] == 59.98
    assert not [path for path in unresolved if ".houseTypes" in path]


def test_housing_type_with_one_house_type_still_gets_that_house_type_and_keeps_its_area_from_the_source_cell():
    housing_type, _ = one_type(house_type_pages(), rowIds=["P3:T1:R2"], housingType="049.7600A")

    assert [(item["name"], item["exclusiveArea"]) for item in housing_type["houseTypes"]] == [("49A", 49.76)]
    assert housing_type["exclusiveArea"] == 49.76


def test_house_types_of_a_cited_row_of_another_housing_type_are_not_used():
    housing_type, _ = one_type(house_type_pages(), rowIds=["P3:T1:R3", "P3:T1:R7", "P3:T1:R8"], housingType="059.9000B")

    assert [(item["name"], item["exclusiveArea"]) for item in housing_type["houseTypes"]] == [
        ("59B", 59.93), ("59B1", 59.92)]


def test_house_types_of_several_rows_of_one_housing_type_stay_one_per_house_type_name():
    # 신청형 칸을 병합하지 않고 행마다 다시 적은 표. 같은 면적 59.98이어도 이름이 다른 59A·59A1은 따로다.
    rows = tables.SupplyTable.of(3, 1, [
        ["주택형", "주택타입", "주거전용", "주거공용", "예비자"],
        ["059.9000A", "59A", "59.98", "21.5309", "10"],
        ["059.9000A", "59A1", "59.98", "21.5400", ""],
        ["059.9000A", "59C", "59.99", "21.5345", ""],
    ], None, False).rows
    selected = pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[3], supply_row_ids=frozenset(row.id for row in rows),
        supply_row_text={row.id: row.text for row in rows}, notice_text="body", notice_pages=[1],
        complex_candidates=[], table_structure_fallback=False, ambiguous_supply_tables=False,
        supply_row_values={row.id: row.values for row in rows},
        supply_row_subtypes={row.id: row.subtypes for row in rows if row.subtypes},
    )

    housing_type, _ = one_type(selected, rowIds=["P3:T1:R2", "P3:T1:R3", "P3:T1:R4"], housingType="059.9000A")

    assert [(item["name"], item["exclusiveArea"], item["commonArea"]) for item in housing_type["houseTypes"]] == [
        ("59A", 59.98, 21.5309), ("59A1", 59.98, 21.54), ("59C", 59.99, 21.5345)]


# 다산진건 장기전세(GH 808)를 줄인 표. 주택형(신청형) 칸이 주택타입 행으로 병합되고, 모집호수를 계·입주자·예비자로 나눈다.
# 대기 예비자는 기존 대기자라 모집수가 아니다.
SPLIT_COUNT_TABLE = [
    ["단지명", "주택형", "주택 타입", "주거 전용", "주거 공용", "임대보증금(천원)", "대기 예비자", "모집호수", None, None],
    [None, None, None, None, None, None, None, "계", "입주자", "예비자"],
    ["다산 진건 데시앙", "72", "72A", "72.98", "23.9", "269,680", "8", "13", "0", "13"],
    [None, "84", "84A", "84.98", "25.90", "288,550", "6", "41", "0", "41"],
    [None, None, "84B", "84.95", "27.27", "288,550", "14", "13", "0", "13"],
]


def table_pages(cells, page=5):
    table = tables.SupplyTable.of(page, 1, cells, None, False)
    rows = table.rows
    return pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=[page], supply_row_ids=frozenset(row.id for row in rows),
        supply_row_text={row.id: row.text for row in rows}, notice_text="body", notice_pages=[1],
        complex_candidates=[], table_structure_fallback=False, ambiguous_supply_tables=False,
        supply_row_values={row.id: row.values for row in rows},
        supply_row_subtypes={row.id: row.subtypes for row in rows if row.subtypes}, supply_tables=[table],
    )


def dasan(selected, *items, shared=()):
    return supplied(selected, *({"complexName": "다산 진건 데시앙", "target": "일반", **item} for item in items),
                    shared=shared)


def own(name, label, winner, reserve):
    """한 칸만 멤버인 모집수·예비자 수량."""
    return [("WINNER", winner, [f"{name}/{label}"]), ("RESERVE", reserve, [f"{name}/{label}"])]


@pytest.mark.parametrize("labels", [("72", "84", "84"), ("72A", "84A", "84B")])
def test_rows_split_by_house_type_are_labeled_with_the_house_type_column(labels):
    # 주택타입 행마다 뽑으면 표기는 주택타입이다(사용자 결정). 모델이 주택형(84)을 써도 같은 행의 원문 칸이라 미확인으로 버리지 않는다.
    # 72는 주택타입이 하나뿐이라 그 행만으로는 단위를 모르지만, 같은 표의 다른 행이 주택타입 단위라 72A다.
    result = dasan(table_pages(SPLIT_COUNT_TABLE), *(
        {"rowIds": [f"P5:T1:R{number}"], "housingType": label} for number, label in zip((3, 4, 5), labels)))

    assert [(item["name"], item["houseTypes"]) for item in result.sheet["housingTypes"]] == [
        ("72A", []), ("84A", []), ("84B", [])]
    # 모델이 빠뜨린 면적은 그 주택타입 행의 칸에서 읽는다. 주택형 칸이 시작하는 행(72A·84A)도 같다.
    assert [item["exclusiveArea"] for item in result.sheet["housingTypes"]] == [72.98, 84.98, 84.95]
    assert not [path for path in result.unresolved() if path.endswith(".name")]


@pytest.mark.parametrize("label", ["84", "84A"])
def test_rows_by_housing_type_are_labeled_with_the_housing_type_column(label):
    # 주택형마다 뽑으면 표기는 주택형이고, 그 주택형 칸이 걸친 주택타입은 houseTypes다.
    result = dasan(table_pages(SPLIT_COUNT_TABLE), {"rowIds": ["P5:T1:R3"], "housingType": "72"},
                   {"rowIds": ["P5:T1:R4", "P5:T1:R5"], "housingType": label})

    assert [(item["name"], [house["name"] for house in item["houseTypes"]]) for item in result.sheet["housingTypes"]] == [
        ("72", ["72A"]), ("84", ["84A", "84B"])]
    # 주택타입 하나뿐인 72는 그 행의 모집수다. 84는 주택타입 행마다 적힌 모집수의 합(41+13)이다(사용자 결정).
    assert readable(result.sheet)["quotas"] == [*own("72", "전체", 13, 13), *own("84", "전체", 54, 54)]
    assert result.unresolved() == set()


def test_housing_type_row_citing_only_its_first_house_type_row_does_not_take_that_rows_count():
    # 모델이 84 칸이 시작하는 84A 행만 가리켜도 84의 모집수는 84A(41)가 아니라 84A·84B의 합이다.
    result = dasan(table_pages(SPLIT_COUNT_TABLE), {"rowIds": ["P5:T1:R3"], "housingType": "72"},
                   {"rowIds": ["P5:T1:R4"], "housingType": "84"})

    assert result.sheet["housingTypes"][1]["name"] == "84"
    assert readable(result.sheet)["quotas"][2:] == own("84", "전체", 54, 54)


def test_housing_type_row_count_is_checked_against_the_sum_of_its_house_type_rows():
    # 주택형 단위 수량은 주택타입 행마다 적힌 수의 합(41+13)과 대조한다. 한 행의 41은 그 주택형의 수가 아니다.
    selected = table_pages(SPLIT_COUNT_TABLE)
    item = {"rowIds": ["P5:T1:R4", "P5:T1:R5"], "housingType": "84"}
    result = dasan(selected, {**item, "totalSupplyHouseholdCount": 54, "supplyHouseholdCount": 54, "reserveCount": 54})
    assert readable(result.sheet)["quotas"] == own("84", "전체", 54, 54) and result.unresolved() == set()

    result = dasan(selected, {**item, "totalSupplyHouseholdCount": 41, "supplyHouseholdCount": 41, "reserveCount": 41})
    assert result.sheet["quotas"] == []
    assert {"cells[0].winner", "cells[0].reserve"} <= result.unresolved()
    # 단지 주택타입(houseTypes)은 세부 타입마다 따로 둔다.
    assert [house["name"] for house in result.sheet["housingTypes"][0]["houseTypes"]] == ["84A", "84B"]


@pytest.mark.parametrize("cells", [
    # 84B의 모집수가 없다.
    [[None, None, "84B", "84.95", "27.27", "288,550", "14", "", "", ""]],
    # 84B 예비자 칸이 84A와 병합된 공통 수량이다(모집호수 계는 행마다 있다).
    [[None, None, "84B", "84.95", "27.27", "288,550", "14", "13", "0", None]],
    # 84B 수량이 84C와 병합된 공통 수량이다.
    [[None, None, "84B", "84.95", "27.27", "288,550", "14", "13", "0", "13"],
     [None, None, "84C", "84.98", "26.23", "288,550", "10", None, None, None]],
])
# 모델이 84A 한 타입의 수(41)를 내도 합을 정하지 못한 주택형에서는 확인된 값이 아니다.
@pytest.mark.parametrize("model_counts", [{}, {"totalSupplyHouseholdCount": 41, "supplyHouseholdCount": 41,
                                               "reserveCount": 41}])
def test_housing_type_row_count_is_left_empty_when_a_house_type_count_is_missing_or_shared(cells, model_counts):
    table = [*SPLIT_COUNT_TABLE[:4], *cells]
    block = [f"P5:T1:R{number}" for number in range(4, len(table) + 1)]
    result = dasan(table_pages(table), {"rowIds": block, "housingType": "84", **model_counts})

    assert result.sheet["quotas"] == []
    assert ({"cells[0].winner", "cells[0].reserve"} <= result.unresolved()) == bool(model_counts)


# 울산다운2 A-9블록을 줄인 표. 공급형 칸 하나가 주택형 행들에 걸치고 당첨자·예비자 칸도 그 행 전체에 하나로 병합된다.
# 주거약자용 형은 자기 수량을 따로 적고 공급대상 칸만 위 형과 함께 쓴다.
MERGED_COUNT_TABLE = [
    ["공급형", "주택형", "공급대상", "금회 공급호수", None, "주거 전용"],
    [None, None, None, "당첨자", "예비자", None],
    ["55", "55A", "신혼부부", "103", "100", "55.93"],
    [None, "55B", None, None, None, "55.97"],
    ["55 (주거약자용)", "55AS", None, "19", "10", "55.93"],
]


def test_merged_count_under_a_supply_type_cell_is_a_shared_recruitment_not_a_housing_type():
    # 병합 모집과 주택형은 다르다(사용자 교정). 공고문이 '주택형'이라 적은 55A·55B·55AS가 공고 주택형이고, 55A·55B에 걸쳐
    # 병합된 당첨자 103 + 예비자 100은 두 주택형이 함께 쓰는 모집수다. 55AS는 자기 수(19 + 10)를 가진다.
    # 모델이 55AS 대상을 공급형 칸의 괄호 구분 없이 적어도 표를 코드로 묶을 때와 같이 주거약자용 대상으로 남는다.
    result = supplied(table_pages(MERGED_COUNT_TABLE),
                      *({"rowIds": [f"P5:T1:R{number}"], "housingType": label, "target": "신혼부부"}
                        for number, label in ((3, "55A"), (4, "55B"), (5, "55AS"))))

    assert [(item["name"], item["exclusiveArea"], item["houseTypes"]) for item in result.sheet["housingTypes"]] == [
        ("55A", 55.93, []), ("55B", 55.97, []), ("55AS", 55.93, [])]
    joined = ["55A/신혼부부", "55B/신혼부부"]
    assert readable(result.sheet)["quotas"] == [("WINNER", 203, joined), ("RESERVE", 100, joined),
                                                *own("55AS", "신혼부부(주거약자용)", 29, 10)]
    assert not {path for path in result.unresolved() if path.endswith((".winner", ".reserve"))}


def test_supply_type_qualifier_comes_only_from_the_rows_that_name_the_housing_type():
    # 모델이 병합된 공급대상 칸의 근거로 55A와 55AS 행을 함께 가리켜도, 괄호 구분은 주택형 칸에 그 주택형이 적힌 행의 것이다.
    # 55A에 55AS 행의 '주거약자용'이 붙으면 55A 신혼부부가 주거약자용 신청자격 경로만 받는다.
    both = ["P5:T1:R3", "P5:T1:R5"]
    result = supplied(table_pages(MERGED_COUNT_TABLE), {"rowIds": both, "housingType": "55A", "target": "신혼부부"},
                      {"rowIds": ["P5:T1:R4"], "housingType": "55B", "target": "신혼부부"},
                      {"rowIds": both, "housingType": "55AS", "target": "신혼부부"})

    assert readable(result.sheet)["cells"] == ["55A/신혼부부", "55B/신혼부부", "55AS/신혼부부(주거약자용)"]


# 주택형 칸 하나가 주택타입 행들에 걸치고(84 = 84A·84B) 당첨자·예비자 칸도 그 행 전체에 하나로 병합된 표.
HOUSE_TYPE_MERGED_COUNT_TABLE = [
    ["주택형", "주택 타입", "공급대상", "당첨자", "예비자", "주거 전용"],
    ["84", "84A", "신혼부부", "103", "100", "84.93"],
    [None, "84B", None, None, None, "84.97"],
    ["59", "59A", None, "19", "10", "59.93"],
]


def test_count_cell_merged_over_all_house_type_rows_is_the_housing_type_count():
    # 공고문이 주택형 84 아래 주택타입 84A·84B를 적고 수량 칸 하나를 두 행에 병합했으면 그 칸이 주택형 84의 모집수다.
    # 59가 공급대상 칸의 근거로 84A 행을 함께 가리켜도 그 행의 수량을 나눠 쓰는 것이 아니라 두 형 모두 자기 수가 원문 대조를 통과한다.
    result = supplied(table_pages(HOUSE_TYPE_MERGED_COUNT_TABLE),
                      {"rowIds": ["P5:T1:R2", "P5:T1:R3"], "housingType": "84", "target": "신혼부부",
                       "supplyHouseholdCount": 203, "reserveCount": 100},
                      {"rowIds": ["P5:T1:R2", "P5:T1:R4"], "housingType": "59", "target": "신혼부부",
                       "supplyHouseholdCount": 29, "reserveCount": 10})

    assert [(item["name"], [house["name"] for house in item["houseTypes"]]) for item in result.sheet["housingTypes"]] == [
        ("84", ["84A", "84B"]), ("59", ["59A"])]
    assert readable(result.sheet)["quotas"] == [*own("84", "신혼부부", 203, 100), *own("59", "신혼부부", 29, 10)]
    assert not {path for path in result.unresolved() if path.endswith((".winner", ".reserve"))}


def test_count_merged_in_only_some_columns_is_not_the_housing_type_count():
    # 당첨자 칸은 55A·55B에 걸쳐 병합됐지만 예비자는 행마다 따로 적혔다. 병합 칸 하나로도, 행마다의 합으로도 정하지 않는다.
    # 모델이 첫 행만 읽은 값(103 + 60, 예비 60)을 내도 확인된 값이 아니다.
    table = [["주택형", "주택 타입", "공급대상", "당첨자", "예비자", "주거 전용"],
             ["55", "55A", "신혼부부", "103", "60", "55.93"], [None, "55B", None, None, "40", "55.97"]]
    result = supplied(table_pages(table), {"rowIds": ["P5:T1:R2", "P5:T1:R3"], "housingType": "55", "target": "신혼부부",
                                           "supplyHouseholdCount": 163, "reserveCount": 60})

    assert [house["name"] for house in result.sheet["housingTypes"][0]["houseTypes"]] == ["55A", "55B"]

    assert result.sheet["quotas"] == []
    assert {"cells[0].winner", "cells[0].reserve"} <= result.unresolved()


# 예비입주자만 모집하는 공고(51번 모양)를 줄인 표. 주택형(신청형) 칸이 주택타입 행에 걸치고 '금회 예비입주자 모집 호수' 칸 하나가
# 그 행 전체에 병합된다. 059.9000A는 주택타입 행 사이에 면적만 적은 이어지는 줄이 낀다.
RESERVE_ONLY_HOUSE_TYPE_TABLE = [
    ["주택형 (신청형)", "주택 타입", "주거 전용", "주거 공용", "관리호수", "현재 예비자", "금회 예비입주자 모집 호수"],
    ["049.7600A", "49A", "49.76", "17.8623", "172", "0", "35"],
    ["059.9000A", "59A", "59.98", "21.5309", "492", "0", "125"],
    [None, "59A1", None, None, None, None, None],
    [None, None, "59.99", "21.5345", None, None, None],
    [None, "59C", None, None, None, None, None],
    ["059.9000B", "59B", "59.93", "21.5130", None, None, "25"],
    [None, "59B1", "59.92", "21.5094", None, None, None],
]


def reserve_only_supply(table, *counts: int, first_row_only: bool = False):
    """51번 공고 모양의 모델 답. 주택형마다 그 주택형 칸이 걸친 행(또는 칸이 시작하는 행만)을 가리키고 예비자 수만 답한다."""
    blocks = (("049.7600A", [2]), ("059.9000A", [3, 4, 5, 6]), ("059.9000B", [7, 8]))
    types = [{"key": f"h{at}", "complexName": "", "name": name,
              "rowIds": [f"P3:T1:R{number}" for number in (rows[:1] if first_row_only else rows)], "targets": ["t1"]}
             for at, (name, rows) in enumerate(blocks, 1)]
    quotas = [{"kind": "RESERVE", "count": count, "members": [[housing_type["key"], "t1"]],
               "rowIds": housing_type["rowIds"][:1]} for housing_type, count in zip(types, counts)]
    answer = {"data": {"housingTypes": types, "targets": [{"key": "t1", "label": "일반"}], "quotas": quotas,
                       "leaseTerms": []}}
    return model_supply(table_pages(table, page=3), answer)


# 모델은 주택형 칸이 걸친 행을 모두 가리키기도 하고 칸이 시작하는 행만 가리키기도 한다.
@pytest.mark.parametrize("first_row_only", [False, True])
def test_reserve_count_merged_over_the_house_type_rows_of_a_reserve_only_table_is_the_housing_type_count(first_row_only):
    # 모델이 맞게 읽은 예비자 수(125·25)가 원문 대조에서 비워졌다. 예비자만 모집하는 표는 입주자 열이 없어 주택타입 행을 묶은
    # 주택형의 모집수를 정하지 못했고, 주택타입 행 사이에 면적 줄이 끼면 병합된 수량 칸도 주택형의 칸으로 보지 않았다.
    result = reserve_only_supply(RESERVE_ONLY_HOUSE_TYPE_TABLE, 35, 125, 25, first_row_only=first_row_only)

    assert readable(result.sheet)["quotas"] == [*own("049.7600A", "전체", 35, 35), *own("059.9000A", "전체", 125, 125),
                                                *own("059.9000B", "전체", 25, 25)]
    assert not {path for path in result.unresolved() if path.endswith((".winner", ".reserve"))}


@pytest.mark.parametrize("first_row_only", [False, True])
def test_reserve_count_of_a_reserve_only_table_is_still_checked_against_the_merged_cell(first_row_only):
    # 병합 칸의 수(125)가 아닌 값(관리호수 492)은 확인된 값이 아니다.
    result = reserve_only_supply(RESERVE_ONLY_HOUSE_TYPE_TABLE, 35, 492, 25, first_row_only=first_row_only)

    assert ("RESERVE", 492, ["059.9000A/전체"]) not in readable(result.sheet)["quotas"]
    assert "cells[1].reserve" in result.unresolved()


@pytest.mark.parametrize("first_row_only", [False, True])
def test_reserve_count_merged_into_the_next_housing_type_is_not_one_housing_types_count(first_row_only):
    # 예비자 칸이 다음 주택형 행까지 병합되면 두 주택형이 함께 쓰는 수다. 한 주택형의 수로 확인하지 않는다.
    table = [*RESERVE_ONLY_HOUSE_TYPE_TABLE[:6], ["059.9000B", "59B", "59.93", "21.5130", None, None, None],
             RESERVE_ONLY_HOUSE_TYPE_TABLE[7]]
    result = reserve_only_supply(table, 35, 125, 25, first_row_only=first_row_only)

    quotas = readable(result.sheet)["quotas"]
    assert not [quota for quota in quotas if quota[2] in (["059.9000A/전체"], ["059.9000B/전체"])]
    assert {"cells[1].reserve", "cells[2].reserve"} <= result.unresolved()


@pytest.mark.parametrize(("count", "confirmed"), [(125, True), (100, False)])
def test_reserve_counts_written_on_each_house_type_row_of_a_reserve_only_table_sum_to_the_housing_type_count(count, confirmed):
    # 예비자 수를 주택타입 행마다 적은 표는 그 합(100 + 25)이 주택형의 모집수·예비자 수다. 한 행의 수(100)는 주택형의 수가 아니다.
    table = [RESERVE_ONLY_HOUSE_TYPE_TABLE[0], ["059.9000B", "59B", "59.93", "21.5130", "40", "0", "100"],
             [None, "59B1", "59.92", "21.5094", "13", "0", "25"]]
    answer = {"data": {
        "housingTypes": [{"key": "h1", "complexName": "", "name": "059.9000B", "rowIds": ["P3:T1:R2", "P3:T1:R3"],
                          "targets": ["t1"]}],
        "targets": [{"key": "t1", "label": "일반"}], "leaseTerms": [],
        "quotas": [{"kind": "RESERVE", "count": count, "members": [["h1", "t1"]], "rowIds": ["P3:T1:R2"]}]}}

    result = model_supply(table_pages(table, page=3), answer)

    assert (("RESERVE", count, ["059.9000B/전체"]) in readable(result.sheet)["quotas"]) == confirmed
    assert ("cells[0].reserve" in result.unresolved()) != confirmed
    assert ("WINNER", 125, ["059.9000B/전체"]) in readable(result.sheet)["quotas"]


def test_housing_type_row_sum_does_not_let_other_house_type_cells_confirm_other_values():
    # 합을 위해 84B 행을 읽어도 84A 행만 가리킨 주택형의 면적은 84A 칸과만 대조한다(84B 면적 84.95는 아니다).
    result = dasan(table_pages(SPLIT_COUNT_TABLE), {"rowIds": ["P5:T1:R3"], "housingType": "72"},
                   {"rowIds": ["P5:T1:R4"], "housingType": "84", "exclusiveArea": 84.95})

    assert readable(result.sheet)["quotas"][2:] == own("84", "전체", 54, 54)
    assert result.sheet["housingTypes"][1]["exclusiveArea"] is None
    assert "housingTypes[1].exclusiveArea" in result.unresolved()


def test_label_found_in_neither_housing_column_stays_unresolved():
    result = dasan(table_pages(SPLIT_COUNT_TABLE), {"rowIds": ["P5:T1:R5"], "housingType": "85B"})

    assert result.sheet["housingTypes"][0]["name"] is None
    assert {"path": "housingTypes[0].name", "reason": "추출한 값이 같은 의미의 원문 표 열에서 확인되지 않음"} in (
        result.sheet["unresolvedFields"])


def test_recruitment_count_is_entrants_plus_reserves_and_reserve_count_stays_reserves():
    # 입주자 0 + 예비자 13 = 계 13. 모집수는 그 합이고 예비자 수량은 예비자 수 그대로다.
    selected = table_pages(SPLIT_COUNT_TABLE)
    result = dasan(selected, {"rowIds": ["P5:T1:R5"], "housingType": "84B", "totalSupplyHouseholdCount": 13,
                              "supplyHouseholdCount": 13, "reserveCount": 13})
    assert readable(result.sheet)["quotas"] == own("84B", "전체", 13, 13)

    # 입주자 수만(0) 낸 모집수는 원문 모집호수와 다르다.
    result = dasan(selected, {"rowIds": ["P5:T1:R5"], "housingType": "84B", "totalSupplyHouseholdCount": 0,
                              "supplyHouseholdCount": 0})
    assert readable(result.sheet)["quotas"] == [("RESERVE", 13, ["84B/전체"])]
    assert "cells[0].winner" in result.unresolved()


def test_recruitment_count_missing_from_the_model_is_read_from_the_source_row():
    # 작업 121: 모델이 수량을 모두 빠뜨렸다. 이 주택형만 가리키는 수량 행이 하나면 그 칸에서 읽는다.
    result = dasan(table_pages(SPLIT_COUNT_TABLE), *({"rowIds": [f"P5:T1:R{number}"], "housingType": label}
                                                     for number, label in ((3, "72A"), (4, "84A"), (5, "84B"))))

    assert readable(result.sheet)["quotas"] == [*own("72A", "전체", 13, 13), *own("84A", "전체", 41, 41),
                                                *own("84B", "전체", 13, 13)]
    assert result.unresolved() == set()


@pytest.mark.parametrize("table", [
    [["단지명", "주택형", "공급대상", "당첨자 수", "예비자"], ["Test", "26A", "청년", "2", "41"]],
    # 하위 열 없는 모집호수 옆에 예비자 열이 따로 있으면 모집호수는 입주자 수다.
    [["단지명", "주택형", "공급대상", "모집호수", "예비자"], ["Test", "26A", "청년", "2", "41"]],
    # 모집호수를 입주자·예비자로만 나눈 표와, 계가 뒤에 있는 표. 모집호수 머리글은 맨 왼쪽 하위 열(입주자)에만 붙는다.
    [["단지명", "주택형", "공급대상", "모집호수", None], [None, None, None, "입주자", "예비자"],
     ["Test", "26A", "청년", "2", "41"]],
    [["단지명", "주택형", "공급대상", "모집호수", None, None], [None, None, None, "입주자", "예비자", "계"],
     ["Test", "26A", "청년", "2", "41", "43"]],
])
def test_recruitment_count_without_a_leading_total_column_is_entrants_plus_reserves(table):
    selected = table_pages(table, page=1)
    row_id = f"P1:T1:R{len(table)}"
    item = {"rowIds": [row_id], "complexName": "Test", "housingType": "26A", "target": "청년"}

    result = supplied(selected, {**item, "totalSupplyHouseholdCount": 43, "supplyHouseholdCount": 43, "reserveCount": 41})
    assert readable(result.sheet)["quotas"] == own("26A", "청년", 43, 41)
    # 입주자 수만 낸 모집수는 원문과 다르다.
    result = supplied(selected, {**item, "totalSupplyHouseholdCount": 2, "supplyHouseholdCount": 2})
    assert readable(result.sheet)["quotas"] == [("RESERVE", 41, ["26A/청년"])]
    assert "cells[0].winner" in result.unresolved()
    # 모델이 빠뜨리면 입주자와 예비자의 합으로 채운다.
    result = supplied(selected, item)
    assert readable(result.sheet)["quotas"] == own("26A", "청년", 43, 41)


# 탑석센트럴자이(GH 845)를 줄인 표. 모집호수 칸이 세 주택형 행에 세로 병합된 공통 수량이다.
SHARED_COUNT_TABLE = [
    ["단지명", "주택형", "주거 전용", "대기 예비자", "모집호수", None, None],
    [None, None, None, None, "계", "입주자", "예비자"],
    ["탑석 센트럴 자이", "49A", "49.9285", "0", "70", "14", "56"],
    [None, "49B", "49.9091", None, None, None, None],
    [None, "49C", "49.9438", None, None, None, None],
]
JOINED = ["49A/전체", "49B/전체", "49C/전체"]


def test_types_sharing_a_merged_recruitment_count_are_one_quota_over_each_type():
    # 공고가 세 주택형에 모집수 하나(70)를 적었으면 한 모집 단위다. 주택형은 형마다 하나씩 두고
    # (사용자 결정: 공고에서는 묶고 단지 주택타입은 뗀다) 70은 복제하지 않고 세 칸이 함께 쓰는 수량 하나다.
    selected = table_pages(SHARED_COUNT_TABLE, page=2)
    items = [{"rowIds": [f"P2:T1:R{number}"], "complexName": "탑석 센트럴 자이", "housingType": label}
             for number, label in ((3, "49A"), (4, "49B"), (5, "49C"))]

    result = dasan(selected, *items)
    assert [(item["name"], item["exclusiveArea"]) for item in result.sheet["housingTypes"]] == [
        ("49A", 49.9285), ("49B", 49.9091), ("49C", 49.9438)]
    assert readable(result.sheet) == {"cells": JOINED, "quotas": [("WINNER", 70, JOINED), ("RESERVE", 56, JOINED)],
                                      "leaseTerms": []}
    assert result.sheet["unresolvedFields"] == []

    # 한 주택형이 병합 칸이 덮는 행을 모두 가리키면 그 주택형의 수량이다.
    result = dasan(selected, {"rowIds": ["P2:T1:R3", "P2:T1:R4", "P2:T1:R5"], "complexName": "탑석 센트럴 자이",
                              "housingType": "49A"})
    assert readable(result.sheet)["quotas"] == own("49A", "전체", 70, 56)

    # 여러 주택형이 같은 수량 행을 가리켜도 그 수량은 공통 수량이라 형마다 복제하지 않는다.
    result = dasan(selected, *({**item, "rowIds": ["P2:T1:R3", *item["rowIds"]]} for item in items[1:]))
    assert readable(result.sheet)["cells"] == ["49B/전체", "49C/전체"]
    assert result.sheet["quotas"] == []


def test_types_sharing_a_count_are_not_joined_when_their_rents_differ():
    # 임대조건이 다른 주택형은 한 모집 단위에 담을 수 없다. 묶지 않고 공통 수량도 복제하지 않는다.
    selected = table_pages(SHARED_COUNT_TABLE, page=2)
    result = dasan(selected, *({"rowIds": [f"P2:T1:R{number}"], "complexName": "탑석 센트럴 자이",
                                "housingType": label, "rentalDeposit": deposit}
                               for number, label, deposit in ((3, "49A", 1000), (4, "49B", 2000), (5, "49C", 3000))))

    assert readable(result.sheet)["cells"] == JOINED
    assert result.sheet["quotas"] == []


def test_shared_recruitment_count_written_as_the_sum_is_supported():
    # 모델이 세 행의 공통 수량으로 낸 70은 묶은 모집 단위 하나의 모집수가 된다.
    result = dasan(table_pages(SHARED_COUNT_TABLE, page=2), *(
        {"rowIds": [f"P2:T1:R{number}"], "complexName": "탑석 센트럴 자이", "housingType": label}
        for number, label in ((3, "49A"), (4, "49B"), (5, "49C"))),
        shared=[{"rowIndexes": [0, 1, 2], "field": "supplyHouseholdCount", "count": 70, "rowIds": ["P2:T1:R3"]}])

    assert readable(result.sheet)["quotas"] == [("WINNER", 70, JOINED), ("RESERVE", 56, JOINED)]


def test_partly_merged_count_cells_are_summed_once_in_the_joined_quota():
    # 당첨자 수는 행마다, 예비자만 세 행에 병합됐다. 묶은 모집수는 5 + 4 + 3 + 56이고 예비자 56은 한 번만 센다.
    table = [["단지명", "주택형", "당첨자 수", "예비자"], ["Test", "49A", "5", "56"], [None, "49B", "4", None],
             [None, "49C", "3", None]]
    result = supplied(table_pages(table, page=2), *(
        {"rowIds": [f"P2:T1:R{number}"], "complexName": "Test", "housingType": label, "target": "일반"}
        for number, label in ((2, "49A"), (3, "49B"), (4, "49C"))))

    assert readable(result.sheet)["quotas"] == [("WINNER", 68, JOINED), ("RESERVE", 56, JOINED)]


def test_target_values_without_a_target_name_keep_one_cell():
    # 대상 열이 없는 표에서 모델이 target을 빠뜨리고 예비자 수·보증금만 냈다. 대상을 버리면 그 값이 사라진다.
    result = supplied(table_pages(SPLIT_COUNT_TABLE), {
        "rowIds": ["P5:T1:R5"], "complexName": "다산 진건 데시앙", "housingType": "84B", "reserveCount": 13,
        "rentalDeposit": 288550000})

    assert readable(result.sheet)["cells"] == ["84B/전체"]
    assert ("RESERVE", 13, ["84B/전체"]) in readable(result.sheet)["quotas"]


def test_housing_type_row_with_several_targets_does_not_confirm_one_house_type_count_per_target():
    # 합(54)을 대상별로 나눌 근거가 없다. 84A 한 칸(41)과 맞는 대상 수량도 확인된 값이 아니다.
    result = dasan(table_pages(SPLIT_COUNT_TABLE),
                   {"rowIds": ["P5:T1:R4", "P5:T1:R5"], "housingType": "84", "target": "일반",
                    "totalSupplyHouseholdCount": 54},
                   {"rowIds": ["P5:T1:R4", "P5:T1:R5"], "housingType": "84", "target": "우선",
                    "supplyHouseholdCount": 41, "reserveCount": 41})

    # 주택형 모집수 54만 두 칸이 함께 쓰는 수량으로 남는다.
    assert [(kind, count, len(members)) for kind, count, members in readable(result.sheet)["quotas"]] == [
        ("WINNER", 54, 2)]
    assert {"cells[0].winner", "cells[0].reserve"} <= result.unresolved()


# 임대조건표의 전환가능 보증금 한도액 칸(삼척도계). 증액(+)·감액(-) 한도가 위아래 줄이고, 옆 열은 전환 뒤 총액이다.
SAMCHEOK_CONVERSION_TABLE = [
    ["단지명", "주 택 형", "임대조건", None, None, None, "전환가능 보증금 한도액(원)", "최대전환시 임대조건", None],
    [None, None, "임대보증금(원)", None, None, "월임대료 (원)", None, None, None],
    [None, None, None, None, None, None, None, "보증금 (원)", "월임대료 (원)"],
    [None, None, "계", "계약금", "잔금", None, None, None, None],
    ["삼척도계", "46", "24,546,000", "1,200,000", "23,346,000", "92,720", "(+) 6,000,000", "30,546,000", "62,720"],
    [None, None, None, None, None, None, "(-) 20,000,000", "4,546,000", "151,050"],
]


@pytest.mark.parametrize(("converted", "expected"), [
    (6000000, 6000000),
    (20000000, None),  # 감액 한도
    (30546000, None),  # 최대전환 시 보증금(전환 뒤 총액)
])
def test_converted_deposit_is_confirmed_only_against_the_increase_limit_cell(converted, expected):
    result = supplied(table_pages(SAMCHEOK_CONVERSION_TABLE, page=2), {
        "rowIds": ["P2:T1:R5", "P2:T1:R6"], "complexName": "삼척도계", "housingType": "46", "target": "일반",
        "rentalDeposit": 24546000, "monthlyRent": 92720, "convertedDeposit": converted})

    [lease] = result.sheet["leaseTerms"]
    assert (lease["rentalDeposit"], lease["monthlyRent"], lease["convertedDeposit"]) == (24546000, 92720, expected)
    assert ("leaseTerms[0].convertedDeposit" in result.unresolved()) == (expected is None)


# 인천석남 어울림을 줄인 표. 공급호수를 우선·일반 공급으로 나눴고, 대학생·청년의 일반 공급 28은 두 대상 행에 병합된 칸이다.
PRIORITY_GENERAL_TABLE = [
    ["단지명", "주택형", "공급대상", "공급호수", None], [None, None, None, "우선 공급", "일반 공급"],
    ["석남", "21A", "대학생", "5", "28"], [None, None, "청년", "23", None], [None, None, "주거급여 수급자", "3", "3"],
    [None, "26A", "청년", "5", "-"],
]


def seoknam(*targets, table=PRIORITY_GENERAL_TABLE, first_row=3, housing_type="21A", **values):
    return supplied(table_pages(table, page=3), *(
        {"rowIds": [f"P3:T1:R{first_row + offset}"], "complexName": "석남", "housingType": housing_type, "target": target,
         **values.get(target, {})} for offset, target in enumerate(targets)))


def test_recruitment_count_is_priority_plus_general_and_a_general_cell_shared_by_targets_is_one_shared_quota():
    selected = table_pages(PRIORITY_GENERAL_TABLE, page=3)
    items = [{"rowIds": [f"P3:T1:R{number}"], "complexName": "석남", "housingType": housing_type, "target": target}
             for number, housing_type, target in ((3, "21A", "대학생"), (4, "21A", "청년"), (5, "21A", "주거급여 수급자"),
                                                  (6, "26A", "청년"))]

    result = supplied(selected, *items)

    # 대학생 5 + 청년 23 + 함께 쓰는 일반 28 = 56은 나누지 않고 두 칸이 함께 쓰는 수량이다. 주거급여 3 + 3, '-'는 0이다.
    assert readable(result.sheet)["quotas"] == [("WINNER", 56, ["21A/대학생", "21A/청년"]),
                                                ("WINNER", 6, ["21A/주거급여 수급자"]), ("WINNER", 5, ["26A/청년"])]
    assert not [path for path in result.unresolved() if path.endswith(".winner")]


def test_targets_split_by_income_tier_share_their_one_count_row_as_one_shared_quota():
    # 임대조건표만 청년을 소득(×)·소득(○) 금액 구간으로 나눈다. 공급표의 청년 행(우선 23)은 두 구간 대상이 함께 쓴다.
    selected = table_pages(PRIORITY_GENERAL_TABLE, page=3)
    items = [{"rowIds": [f"P3:T1:R{number}"], "complexName": "석남", "housingType": "21A", "target": target, **condition}
             for number, target, condition in ((3, "대학생", {}), (4, "청년", {"applicationCondition": "소득(×)"}),
                                               (4, "청년", {"applicationCondition": "소득(○)"}), (5, "주거급여 수급자", {}))]

    result = supplied(selected, *items)

    assert [(item["label"], item["applicationCondition"]) for item in result.sheet["targets"]] == [
        ("대학생", None), ("청년", "소득(×)"), ("청년", "소득(○)"), ("주거급여 수급자", None)]
    assert readable(result.sheet)["quotas"] == [("WINNER", 56, ["21A/대학생", "21A/청년", "21A/청년"]),
                                                ("WINNER", 6, ["21A/주거급여 수급자"])]


def test_priority_and_general_columns_are_not_read_from_amount_or_reserve_headers():
    # 우선·일반 공급 아래 예비자·보증금 열은 모집수 칸이 아니다. 둘 다 있을 때만 모집수 칸으로 본다.
    for header in (["일반 공급 예비자", "우선 공급 예비자"], ["우선 공급 보증금", "일반 공급 보증금"], ["우선 공급", "비고"],
                   ["공급호수 우선순위", "공급호수 일반순위"]):
        table = tables.SupplyTable.of(1, 1, [["주택형", "공급대상", *header], ["21A", "청년", "5", "28"]], None, False)
        assert not {"priorityCount", "generalCount"} & table.columns.keys(), header


def test_supply_count_split_into_bare_priority_and_general_subheaders_is_the_recruitment_count():
    # 구리갈매 A-1BL 행복주택 공급표는 '공급호수' 아래 하위 머리글을 '우선'·'일반'으로만 적는다. 모집수는 둘의 합이다(13+20).
    table = [["공급형별", "공급대상", "공급호수", None, "세대당 계약면적(㎡)", None],
             [None, None, "우선", "일반", "주거전용", "기타공용"],
             ["46A-1", "신혼부부 한부모가족·기존거주자", "13", "20", "46.94", "33.3824"]]
    selected = table_pages(table, page=2)
    result = supplied(selected, {"rowIds": ["P2:T1:R3"], "housingType": "46A-1", "target": "신혼부부 한부모가족·기존거주자"})

    assert readable(result.sheet)["quotas"] == [("WINNER", 33, ["46A-1/신혼부부 한부모가족·기존거주자"])]


@pytest.mark.parametrize("change", ["target name", "unreadable cell"])
def test_target_counts_are_left_empty_when_a_target_or_a_cell_cannot_be_matched(change):
    # 대상과 수량 행을 하나씩 맞추지 못하거나 읽지 못한 칸이 있으면 대상별 수도, 공통 모집수도, 주택형 합계도 정하지 않는다.
    table = [list(row) for row in PRIORITY_GENERAL_TABLE[:5]]
    targets = ["대학생", "청년", "주거급여 수급자"]
    if change == "target name":
        targets[1] = "청년층"
    else:
        table[3][3] = "확인"
    assert seoknam(*targets, table=table).sheet["quotas"] == []


def test_target_counts_are_read_from_a_recruitment_total_column():
    table = [["단지명", "주택형", "공급대상", "모집호수"], ["석남", "21A", "대학생", "5"], [None, None, "청년", "23"],
             [None, None, "주거급여 수급자", "6"]]
    assert readable(seoknam("대학생", "청년", "주거급여 수급자", table=table, first_row=2).sheet)["quotas"] == [
        ("WINNER", 5, ["21A/대학생"]), ("WINNER", 23, ["21A/청년"]), ("WINNER", 6, ["21A/주거급여 수급자"])]


@pytest.mark.parametrize("header, cells, expected", [
    # 예비입주자 모집 공고: 입주자 열 없이 '금회 모집 예비자'만 적으면 예비자 수가 모집수다.
    (["주택형", "공급대상", "금회 모집 예비자"], ["26", "청년", "110"], own("26", "청년", 110, 110)),
    # '모집'이 없는 예비자 열은 이미 대기 중인 예비자일 수 있어 모집수로 보지 않는다.
    (["주택형", "공급대상", "예비자"], ["26", "청년", "110"], [("RESERVE", 110, ["26/청년"])]),
    # 다른 수량 열(당첨자)이 있는데 읽지 못하면 예비자 수만으로 모집수를 정하지 않는다.
    (["주택형", "공급대상", "당첨자 수", "금회 모집 예비자"], ["26", "청년", "", "110"], [("RESERVE", 110, ["26/청년"])]),
])
def test_reserve_count_is_the_recruitment_count_only_in_a_reserve_only_recruitment_table(header, cells, expected):
    result = dasan(table_pages([header, cells], page=1), {"rowIds": ["P1:T1:R2"], "housingType": "26", "target": "청년"})
    assert readable(result.sheet)["quotas"] == expected


def test_row_without_a_target_gets_its_recruitment_count_on_an_everyone_cell():
    # 모델이 대상을 빠뜨리고 대상 값도 내지 않은 행이다. 멤버 없는 수량을 만들면 추출 결과 전체를 읽지 못한다.
    result = supplied(table_pages([["단지명", "주택형", "모집호수"], ["Test", "49A", "10"]], page=2),
                      {"rowIds": ["P2:T1:R2"], "complexName": "Test", "housingType": "49A"})
    assert readable(result.sheet)["quotas"] == [("WINNER", 10, ["49A/전체"])]


@pytest.mark.parametrize("targets", [["대학생"], ["대학생", "청년층"]])
# 모델이 주택형을 원문 칸과 다르게 적어도(21A형) 같다.
@pytest.mark.parametrize("housing_type", ["21A", "21A형"])
def test_a_count_cell_merged_over_other_targets_does_not_confirm_one_targets_count(targets, housing_type):
    # 대학생 행에서 시작해 청년 행까지 병합된 일반 공급 28은 둘의 공통 수량이다. 코드가 대상별 수를 정하지 못했을 때
    # 모델이 대학생 몫으로 낸 5 + 28은 원문 칸 합과 같아도 확인된 값으로 보지 않는다.
    result = seoknam(*targets, housing_type=housing_type, **{"대학생": {"supplyHouseholdCount": 33}})

    assert not [quota for quota in readable(result.sheet)["quotas"]
                if quota[1] == 33 and len(quota[2]) == 1 and quota[2][0].endswith("/대학생")]
    assert "cells[0].winner" in result.unresolved()


def test_reserve_count_read_for_a_joined_quota_is_not_left_as_unconfirmed():
    table = [["단지명", "주택형", "공급대상", "당첨자 수", "예비자"], ["Test", "49A", "청년", "5", "56"],
             [None, "49B", "청년", "4", None], [None, "49C", "청년", "3", None]]
    result = supplied(table_pages(table, page=2), *(
        {"rowIds": [f"P2:T1:R{number}"], "complexName": "Test", "housingType": label, "target": "청년"}
        for number, label in ((2, "49A"), (3, "49B"), (4, "49C"))))

    joined = ["49A/청년", "49B/청년", "49C/청년"]
    assert readable(result.sheet)["quotas"] == [("WINNER", 68, joined), ("RESERVE", 56, joined)]
    assert result.sheet["unresolvedFields"] == []


def test_an_unconfirmed_value_of_each_joined_type_moves_once_to_the_joined_target():
    # 묶기 전 주택형마다 단 '원문에 없는 대상명' 미확인은 묶은 단위의 대상 하나로 한 번만 옮겨지고, 묶음 수량은 그대로다.
    table = [["단지명", "주택형", "공급대상", "당첨자 수", "예비자"], ["Test", "49A", "청년", "5", "56"],
             [None, "49B", "청년", "4", None], [None, "49C", "청년", "3", None]]
    result = supplied(table_pages(table, page=2), *(
        {"rowIds": [f"P2:T1:R{number}"], "complexName": "Test", "housingType": label, "target": "신혼부부"}
        for number, label in ((2, "49A"), (3, "49B"), (4, "49C"))))

    joined = ["49A/", "49B/", "49C/"]
    assert readable(result.sheet)["quotas"] == [("WINNER", 68, joined), ("RESERVE", 56, joined)]
    assert result.sheet["unresolvedFields"] == [
        {"path": "targets[0].label", "reason": "추출한 값이 같은 의미의 원문 표 열에서 확인되지 않음"}]


def test_a_sheet_answer_with_split_columns_and_income_tiers_reaches_one_shared_quota():
    # 공급 단계 모델의 공급표 모양 답(우선·일반 열마다 수량 칸, 청년 소득 구간 둘)을 그대로 통과시킨다.
    targets = [{"key": "t1", "label": "대학생"}, {"key": "t2", "label": "청년", "applicationCondition": "소득(×)"},
               {"key": "t3", "label": "청년", "applicationCondition": "소득(○)"}, {"key": "t4", "label": "주거급여 수급자"}]
    answer = {"data": {"housingTypes": [{"key": "h1", "complexName": "석남", "name": "21A", "rowIds": ["P3:T1:R3"],
                                         "targets": ["t1", "t2", "t3", "t4"]}], "targets": targets, "quotas": [
        {"kind": "WINNER", "count": 5, "members": [["h1", "t1"]], "rowIds": ["P3:T1:R3"]},
        {"kind": "WINNER", "count": 28, "members": [["h1", "t1"], ["h1", "t2"], ["h1", "t3"]], "rowIds": ["P3:T1:R3"]},
        {"kind": "WINNER", "count": 23, "members": [["h1", "t2"], ["h1", "t3"]], "rowIds": ["P3:T1:R4"]},
        {"kind": "WINNER", "count": 3, "members": [["h1", "t4"]], "rowIds": ["P3:T1:R5"]},
        {"kind": "WINNER", "count": 3, "members": [["h1", "t4"]], "rowIds": ["P3:T1:R5"]}], "leaseTerms": []}}

    result = model_supply(table_pages(PRIORITY_GENERAL_TABLE, page=3), answer)

    # 청년 이름은 청년 행(R4)에서 확인되어 남고, 5 + 23 + 28 = 56은 대학생·청년 구간 둘이 함께 쓰는 수, 주거급여는 3 + 3이다.
    assert [target["label"] for target in result.sheet["targets"]] == ["대학생", "청년", "청년", "주거급여 수급자"]
    assert readable(result.sheet)["quotas"] == [("WINNER", 56, ["21A/대학생", "21A/청년", "21A/청년"]),
                                                ("WINNER", 6, ["21A/주거급여 수급자"])]
    assert result.warnings == []


def test_a_reserve_only_sheet_answer_takes_the_reserve_count_as_the_recruitment_count():
    # 예비입주자 모집 공고(15번 모양): 모델은 예비자 수만 답하고, '금회 모집 예비자'가 모집수로도 읽힌다.
    answer = {"data": {"housingTypes": [{"key": "h1", "complexName": "충북혁신", "name": "26", "rowIds": ["P3:T1:R2"],
                                         "targets": ["t1"]}], "targets": [{"key": "t1", "label": "청년"}],
                       "quotas": [{"kind": "RESERVE", "count": 110, "members": [["h1", "t1"]], "rowIds": ["P3:T1:R2"]}]}}

    result = model_supply(table_pages([["단지명", "주택형", "공급대상", "금회 모집 예비자"], ["충북혁신", "26", "청년", "110"]],
                                      page=3), answer)

    assert readable(result.sheet)["quotas"] == own("26", "청년", 110, 110)


def test_same_name_targets_without_different_conditions_do_not_share_a_count_row():
    # 금액 구간이 아닌 같은 이름 대상 둘(조건이 같거나 없음)은 어느 대상의 수인지 정할 수 없어 대상별 수를 정하지 않는다.
    selected = table_pages(PRIORITY_GENERAL_TABLE, page=3)
    items = [{"rowIds": [f"P3:T1:R{number}"], "complexName": "석남", "housingType": "21A", "target": target, **extra}
             for number, target, extra in ((3, "대학생", {}), (4, "청년", {"monthlyRent": 1}), (4, "청년", {"monthlyRent": 2}),
                                          (5, "주거급여 수급자", {}))]

    assert supplied(selected, *items).sheet["quotas"] == []


def test_units_with_the_same_name_are_told_apart_in_warnings_by_their_first_source_row():
    # 41번 공고 모양: 원문에서 단지명을 확인하지 못한 두 단지의 26.37은 이름이 같아 같은 경고 문구가 두 번 나왔다.
    selected = table_pages([["단지명", "주택형", "공급대상", "모집호수"], ["", "26.37", "청년", "3"],
                            ["", "26.37", "고령자", "4"]], page=2)

    def area_only_warnings(second_rows):
        supply = model_stage(selected, sheet_answer({"data": {"rows": [
            {"rowIds": ["P2:T1:R2"], "complexName": "가단지", "housingType": "26.37", "target": "청년",
             "recruitmentCount": 3},
            {"rowIds": second_rows, "complexName": "나단지", "housingType": "26.37", "target": "고령자",
             "recruitmentCount": 4},
        ]}}))
        supply.tree["data"]["announcement"] = {}
        warnings = checks.review(supply.tree, supply.sheet, [1, 2], ["", ""], [], 0)
        return [warning for warning in warnings if "면적 숫자만" in warning]

    expected = ["26.37(원본 2쪽 표 1의 2행) 주택형 이름: 면적 숫자만 있음(26.37)",
                "26.37(원본 2쪽 표 1의 3행) 주택형 이름: 면적 숫자만 있음(26.37)"]
    assert area_only_warnings(["P2:T1:R3"]) == expected
    # 두 단위가 함께 가진 첫 행(면적을 물려준 위 행 등)은 그 단위만의 행이 있으면 자리로 쓰지 않는다.
    assert area_only_warnings(["P2:T1:R2", "P2:T1:R3"]) == expected


def several_tables_pages(*tables_):
    """여러 표(공급표·임대조건표)를 표 판독기로 읽은 입력."""
    rows = [row for table in tables_ for row in table.rows]
    return pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=sorted({table.page for table in tables_}),
        supply_row_ids=frozenset(row.id for row in rows), supply_row_text={row.id: row.text for row in rows},
        notice_text="body", notice_pages=[1], complex_candidates=[], table_structure_fallback=False,
        ambiguous_supply_tables=False, supply_row_values={row.id: row.values for row in rows},
        supply_row_subtypes={row.id: row.subtypes for row in rows if row.subtypes}, supply_tables=list(tables_))


def lh_warnings(supply, lh_rows, selected):
    """공급 단계 결과를 LH 공급행과 맞댄 경고(모집수 합계, 빠진 주택형)."""
    supply.tree["data"]["announcement"] = {}
    warnings = checks.review(supply.tree, supply.sheet, [1, 2, 3, 4, 5], [""] * 5, lh_rows, 0)
    sheet = supply.sheet.emit(None)
    warnings += checks.missing_housing_types(sheet, supply_grouping.type_cells(selected.supply_tables), lh_rows)
    return [warning for warning in warnings if "LH" in warning]


# 인천논현4 1블록 창업지원주택 공급표. 25B/B1 (주거약자용) 행의 면적 칸은 25A/A1 행과 세로로 병합된 칸이다.
NONHYEON_SUPPLY = [
    ["공급 형별", "공급대상", "건설호수", "금회 모집호수", "세대당 계약면적(㎡)", None, None, None, None, "구조 및 난방"],
    [None, None, None, None, "주거 전용", "주거 공용", "그 밖의 공용면적", None, "합계", None],
    [None, None, None, None, None, None, "기타 공용", "주차장", None, None],
    ["25A/A1", "청년 창업인", "194", "49", "25.77", "11.96", "10.0029", "10.4914", "58.2243", "철근 콘크리트 / 지역난방"],
    ["25B/B1 (주거약자용)", "청년 창업인 중 주거약자", "20", "5", None, None, None, None, None, None],
    ["44A", "청년 창업인", "24", "1", "44.51", "20.6574", "17.2771", "18.1209", "100.5654", None],
]


@pytest.mark.parametrize("model_area", [{"exclusiveArea": 25.77, "commonArea": 11.96}, {}])
def test_type_row_under_a_merged_area_cell_has_the_area_of_the_row_above(model_area):
    # 모델이 25B/B1 행만 가리키면 그 행의 면적 칸은 비어 있어(병합) 25.77이 확인되지 않았다. 면적이 비면 LH의 25.77 두 줄
    # (49 + 5)이 25A/A1의 49와만 맞대져 모집수가 다르다는 경고가 났다.
    selected = table_pages(NONHYEON_SUPPLY, page=4)
    rows = [{"rowIds": ["P4:T1:R4"], "housingType": "25A/A1", "target": "청년 창업인", "totalSupplyHouseholdCount": 49,
             "exclusiveArea": 25.77, "commonArea": 11.96, "constructedHouseholdCount": 194},
            {"rowIds": ["P4:T1:R5"], "housingType": "25B/B1 (주거약자용)", "target": "청년 창업인 중 주거약자",
             "totalSupplyHouseholdCount": 5, "constructedHouseholdCount": 20, **model_area},
            {"rowIds": ["P4:T1:R6"], "housingType": "44A", "target": "청년 창업인", "totalSupplyHouseholdCount": 1,
             "exclusiveArea": 44.51}]
    supply = model_stage(selected, sheet_answer({"data": {"rows": rows}}))
    result = model_supply(selected, rows=rows)

    assert [(item["name"], item["exclusiveArea"], item["commonArea"]) for item in result.sheet["housingTypes"]] == [
        ("25A/A1", 25.77, 11.96), ("25B/B1 (주거약자용)", 25.77, 11.96 if model_area else None), ("44A", 44.51, None)]
    assert not [path for path in result.unresolved() if "Area" in path]
    # 윗행을 근거 행에 넣지 않아 윗행의 모집수(49)가 25B/B1의 수로 확인되지 않는다.
    assert [count for _, count, _ in readable(result.sheet)["quotas"]] == [49, 5, 1]
    lh_rows = [SourceSupplyRow("LH", None, "인천논현4 1블록", "25A/A1 창업인", "25.77", "37.73", "49"),
               SourceSupplyRow("LH", None, "인천논현4 1블록", "25B/B1 창업인(주거약자용)", "25.77", "37.73", "5"),
               SourceSupplyRow("LH", None, "인천논현4 1블록", "44A/A1 창업인", "44.51", "65.1674", "1")]
    assert lh_warnings(supply, lh_rows, selected) == []


def test_merged_area_cell_is_not_inherited_from_a_row_of_another_complex():
    # 단지명 칸이 새로 시작하는 행은 다른 단지다. 그 행의 비어 있는 면적 칸을 윗단지 면적으로 채우지 않는다.
    cells = [["단지명", "주택형", "공급대상", "모집호수", "주거전용"],
             ["가단지", "26A", "청년", "10", "26.71"],
             ["나단지", "26A", "청년", "5", None]]

    values = tables.SupplyTable.of(4, 1, cells, None, False).rows[2].values

    assert "exclusiveArea" not in values


# 수원당수 2단지 공급표. 주택형 26B/C가 세부 타입 26B·26C를 묶고 면적 칸마다 그 순서대로 값을 '/'로 나눠 적는다.
DANGSU_SUPPLY = [
    ["단지", "공급 형별", "공급대상 (계층)", "건설 호수 (호)", "금회모집 호수", "세대당 계약면적(㎡)", None, None, None, None],
    [None, None, None, None, None, "주거 전용", "주거 공용", "그 밖의 공용면적", None, "합계"],
    [None, None, None, None, None, None, None, "기타공용", "주차장", None],
    ["수원 당수 2단지", "14", "대학생, 청년, 주거급여 수급자", "288", "68", "14.88", "8.6635", "1.6589", "6.7491", "31.9515"],
    [None, "26B/C", None, "142", "17", "26.70/ 26.71", "14.3445/ 13.9885", "2.9766/ 2.9777", "12.1104/ 12.1149",
     "56.1315/ 55.7911"],
]


@pytest.mark.parametrize("model_house_types", [
    [{"name": "26B", "exclusiveArea": 26.70, "commonArea": 14.3445}, {"name": "26C", "exclusiveArea": 26.71}], []])
def test_house_types_written_with_slashes_in_one_type_cell_take_their_areas_in_order(model_house_types):
    # 세부 타입 26C는 칸에 'C'로만 적혀 있고 면적 칸은 두 값을 함께 적어, 모델이 낸 세부 타입이 모두 미확인이었다.
    # 세부 타입도 단위 면적도 없으니 LH '26B/26C(전용 26.70)'와 맞는 주택형이 없다는 경고가 났다.
    selected = table_pages(DANGSU_SUPPLY, page=4)
    rows = [{"rowIds": ["P4:T1:R5"], "complexName": "수원 당수 2단지", "housingType": "26B/C",
             "target": "대학생, 청년, 주거급여 수급자", "totalSupplyHouseholdCount": 17, "subtypes": model_house_types}]
    supply = model_stage(selected, sheet_answer({"data": {"rows": rows}}))
    result = model_supply(selected, rows=rows)

    [housing_type] = result.sheet["housingTypes"]
    assert [(item["name"], item["exclusiveArea"], item["commonArea"]) for item in housing_type["houseTypes"]] == [
        ("26B", 26.70, 14.3445), ("26C", 26.71, 13.9885)]
    assert housing_type["exclusiveArea"] == 26.70
    assert not [path for path in result.unresolved() if "housingTypes" in path]
    lh_rows = [SourceSupplyRow("LH", None, "수원당수 A-2블록 행복주택", "26B/26C(대학생,청년, 주거급여수급자)", "26.7",
                               "41.0445", "34")]
    # LH 모집수(34)는 PDF(17)의 두 배라 모집수 경고는 남는다(실제 공고는 단지가 둘이라 LH 단지와 잇지 못해 이 경고도 없다).
    assert [warning for warning in lh_warnings(supply, lh_rows, selected) if "모집수" not in warning] == []


@pytest.mark.parametrize("model_area, confirmed", [(26.70, True), (26.71, False)])
def test_type_area_of_a_slash_area_cell_is_its_first_value(model_area, confirmed):
    selected = table_pages(DANGSU_SUPPLY, page=4)
    result = supplied(selected, {"rowIds": ["P4:T1:R5"], "complexName": "수원 당수 2단지", "housingType": "26B/C",
                                 "target": "대학생, 청년, 주거급여 수급자", "exclusiveArea": model_area})

    assert (result.sheet["housingTypes"][0]["exclusiveArea"] == model_area) is confirmed
    assert ("housingTypes[0].exclusiveArea" in result.unresolved()) is not confirmed


@pytest.mark.parametrize("type_cell, area_cell", [("26B/C", "26.70"), ("26B/C", "26.70/ 26.71/ 26.72"), ("26B", "26.70/ 26.71")])
def test_slash_house_types_need_one_area_per_name(type_cell, area_cell):
    cells = [row[:] for row in DANGSU_SUPPLY]
    cells[4][1], cells[4][5] = type_cell, area_cell

    assert tables.SupplyTable.of(4, 1, cells, None, False).rows[4].subtypes == ()


# 수원당수 임대조건표. 공급표는 대상을 '대학생, 청년, 주거급여 수급자' 한 칸에 적고, 임대조건표는 소득 유무로 나눠 적는다.
DANGSU_RENT = [
    ["단지", "공급 형별", "공급대상(계층)", "임대조건", None, None, None, "전환가능 보증금 한도액(천원)"],
    [None, None, None, "임대보증금(천원)", None, None, "월임대료 (원)", None],
    [None, None, None, "계", "계약금", "잔금", None, None],
    ["수원당수 2단지", "14", "대학생, 청년(소득無)", "23,120", "1,000", "22,120", "102,110", "+ 8,000"],
    [None, None, "청년(소득有)", "24,480", None, "23,480", "108,120", "+ 9,000"],
    [None, None, "주거급여수급자", "37,200", None, "36,200", "164,300", "+ 19,000"],
]


def dangsu_targets(*targets):
    """수원당수 2단지 14형 공급표 행과 임대조건표 행을 함께 가리킨 대상들(이름, 신청 조건, 임대조건표 행 번호)."""
    selected = several_tables_pages(tables.SupplyTable.of(4, 1, DANGSU_SUPPLY[:4], None, False),
                                    tables.SupplyTable.of(5, 1, DANGSU_RENT, None, False))
    amounts = {4: (23120000, 102110), 5: (24480000, 108120), 6: (37200000, 164300)}
    return supplied(selected, *({"rowIds": ["P4:T1:R4", f"P5:T1:R{row}"], "complexName": "수원 당수 2단지",
                                 "housingType": "14", "target": label, "applicationCondition": condition,
                                 "rentalDeposit": amounts[row][0], "monthlyRent": amounts[row][1]}
                                for label, condition, row in targets))


@pytest.mark.parametrize("targets", [
    # 임대조건표 칸 그대로
    [("대학생, 청년(소득無)", None, 4), ("청년(소득有)", None, 5), ("주거급여수급자", None, 6)],
    # 공급 단계가 소득 구간을 이름 대신 신청 조건에 적은 모양(프롬프트: 같은 label, applicationCondition에 구간)
    [("대학생·청년", "소득無", 4), ("청년", "소득有", 5), ("주거급여수급자", None, 6)],
    [("대학생·청년(소득 없음)", "소득 없음", 4), ("청년(소득 있음)", "소득 있음", 5), ("주거급여 수급자", None, 6)],
])
def test_targets_listed_in_one_cell_or_split_by_income_in_the_rent_table_are_confirmed(targets):
    # 수원당수: 모델이 낸 이름이 원문 칸 '대학생, 청년(소득無)'과 쉼표·가운뎃점·소득 표기만 달라 모두 미확인이었다.
    # 확인한 이름은 원문 칸의 이름(소득 구분 포함)이라 소득 유무로 나뉜 두 대상이 이름으로도 갈린다.
    result = dangsu_targets(*targets)

    assert [target["label"] for target in result.sheet["targets"]][:2] == ["대학생, 청년(소득 없음)", "청년(소득 있음)"]
    assert result.sheet["targets"][2]["label"].replace(" ", "") == "주거급여수급자"
    assert not [path for path in result.unresolved() if path.endswith(".label")]


def test_a_name_split_from_a_listed_cell_is_not_an_income_split_targets_name():
    # 소득 유무로 보증금이 나뉘면 대상이 둘이다(사용자 결정). 가리킨 임대조건표 칸에 소득 구분이 있으면, 공급표 나열 칸
    # '대학생, 청년, 주거급여 수급자'에서 쪼갠 '청년'·'대학생'은 소득 구분 없는 이름의 근거가 아니다.
    result = dangsu_targets(("대학생", None, 4), ("청년", None, 5))

    assert {target["label"] for target in result.sheet["targets"]} == {""}
    assert "targets[0].label" in result.unresolved()


@pytest.mark.parametrize("label, condition", [
    ("취업준비생", None), ("청년(소득 있음)", None), ("대학생·청년", None), ("청년", "소득有"), ("대학생·청년", "소득 있음"),
])
def test_target_not_written_in_the_cited_cells_stays_unconfirmed(label, condition):
    # 원문에 없는 이름(취업준비생), 가리킨 행과 다른 소득 구분(이름이나 신청 조건), 쉼표로 나열한 이름을 이어 붙였지만 칸에 적힌
    # 소득 구분을 뺀 이름은 확인된 이름이 아니다.
    selected = several_tables_pages(tables.SupplyTable.of(5, 1, DANGSU_RENT, None, False))

    result = supplied(selected, {"rowIds": ["P5:T1:R4"], "complexName": "수원당수 2단지", "housingType": "14",
                                 "target": label, "applicationCondition": condition})

    assert [target["label"] for target in result.sheet["targets"]] == [""]
    assert "targets[0].label" in result.unresolved()


def test_commas_inside_parentheses_do_not_split_a_target_name():
    # 영구임대 '가군(생계·의료급여 수급자, 국가유공자 등)'의 괄호 안 쉼표는 대상 나열이 아니다. 괄호 안 조각은 대상 이름이 아니다.
    rows = [["주택형", "공급대상", "모집호수"], ["26A", "가군(생계·의료급여 수급자, 국가유공자 등), 나군", "10"]]
    selected = table_pages(rows, page=2)

    def label(target):
        result = supplied(selected, {"rowIds": ["P2:T1:R2"], "housingType": "26A", "target": target})
        return result.sheet["targets"][0]["label"]

    assert label("가군(생계·의료급여 수급자, 국가유공자 등)") == "가군(생계·의료급여 수급자, 국가유공자 등)"
    assert label("나군") == "나군"
    assert label("국가유공자 등)") == ""


def test_count_of_a_listed_target_cell_is_not_multiplied_by_its_targets():
    # 수원당수 2단지 14형의 모집 68은 '대학생, 청년, 주거급여 수급자' 칸 하나의 수다. 대상 셋이 같은 행을 가리켜도 68은 한 번만 센다.
    selected = table_pages(DANGSU_SUPPLY[:4], page=4)
    result = supplied(selected, *({"rowIds": ["P4:T1:R4"], "complexName": "수원 당수 2단지", "housingType": "14",
                                   "target": label} for label in ("대학생", "청년", "주거급여 수급자")))

    assert result.sheet["housingTypes"][0]["exclusiveArea"] == 14.88
    assert sum(count for kind, count, _ in readable(result.sheet)["quotas"] if kind == "WINNER") == 68


# 인천논현 영구·국민임대 예비자 모집표(328번 공고 4쪽 표)의 머리글과 서창14단지 행을 표 판독기가 읽은 셀 그대로 옮겼다.
# 단지 칸은 글자마다 띄어 읽히고, 대상 칸이 소득 유무를 '(소득無)'로 적으며, 대상 행마다 전환 감액 줄 '(-)23,000'이 끼어 있다.
NONHYEON_RESERVE = [
    ["단 지", "공급 형별", "공급 대상", "대기 중인 예비 자수", "모집 예비 자수 (60)", "세대당 계약면적(㎡)", None, None, None, None,
     "임대조건", None, None, None, "전환가능 보증금 한도액 (천원)", "최대전환 시 임대조건", None, "최대 거주 기간 (년)"],
    [None, None, None, None, None, "주거 전용", "주거 공용", "그 밖의 공용면적", None, "합계", "임대보증금(천원)", None, None,
     "월 임대료 (원)", None, None, None, None],
    [None, None, None, None, None, None, None, "기타 공용", "주차장", None, "계", "계약 금 (5%)", "잔금 (95%)", None, None,
     "임대 보증금 (천원)", "월 임대료 (원)", None],
    ["서 창 14 단 지", "26A", "대학생· 청년 (소득無)", "16", "20", "26.8", "13.5268", "2.4296", "10.2734", "53.0298", "28,220",
     "1,411", "26,809", "131,690", "(+)14,000", "42,220", "61,690", "10"],
    [None, None, None, None, None, None, None, None, None, None, None, None, None, None, "(-)23,000", "5,220", "198,770", None],
    [None, None, "청년 (소득有)", None, None, None, None, None, None, None, "29,880", "1,494", "28,386", "139,440", "(+)15,000",
     "44,880", "64,440", None],
    [None, None, None, None, None, None, None, None, None, None, None, None, None, None, "(-)24,000", "5,880", "209,440", None],
]


@pytest.mark.parametrize("targets", [
    # 원문 그대로, 소득 표기를 맞춘 이름, 공급 단계 프롬프트대로 소득 구간을 신청 조건에 적은 이름
    [("대학생· 청년 (소득無)", None), ("청년 (소득有)", None)],
    [("대학생·청년(소득 없음)", None), ("청년(소득 있음)", None)],
    [("대학생·청년", "소득無"), ("청년", "소득有")],
    [("대학생· 청년", "소득 없음"), ("청년", "소득 있음")],
])
def test_income_marked_targets_of_one_table_keep_their_confirmed_name(targets):
    # 인천논현(328): 확인 뒤 '대학생·청년(소득 없음)'으로 바뀐 이름이 원문 대조에서 다시 지워졌고, 재추출에서는 모델이 소득 구간을
    # 신청 조건(소득無·소득有)에 적고 이름에서 빼 원문 칸 '대학생· 청년 (소득無)'과 맞지 않았다.
    selected = table_pages(NONHYEON_RESERVE, page=4)
    items = [{"rowIds": ["P4:T1:R4", "P4:T1:R5", "P4:T1:R6", "P4:T1:R7"], "complexName": "서 창 14 단 지",
              "housingType": "26A", "target": label, "applicationCondition": condition,
              "rentalDeposit": deposit, "monthlyRent": rent}
             for (label, condition), deposit, rent in zip(targets, (28220000, 29880000), (131690, 139440))]

    result = supplied(selected, *items, shared=[
        {"rowIndexes": [0, 1], "field": "reserveCount", "count": 20, "rowIds": ["P4:T1:R4"]}])

    assert [target["label"] for target in result.sheet["targets"]] == ["대학생·청년(소득 없음)", "청년(소득 있음)"]
    assert not [path for path in result.unresolved() if path.endswith(".label")]


def test_income_tier_in_the_condition_must_be_the_one_written_in_the_cited_cell():
    # 같은 이름에 다른 소득 구분을 붙인 대상(대학생·청년 소득有)은 원문에 없는 이름이다.
    result = supplied(table_pages(NONHYEON_RESERVE, page=4),
                      {"rowIds": ["P4:T1:R4", "P4:T1:R6"], "complexName": "서 창 14 단 지", "housingType": "26A",
                       "target": "대학생·청년", "applicationCondition": "소득有"})

    assert [target["label"] for target in result.sheet["targets"]] == [""]


@pytest.mark.parametrize("cell, expected", [
    ("서 창 14 단 지", "서창14단지"), ("논 현 4 단 지", "논현4단지"), ("수원 당수 1단지", "수원 당수 1단지"),
    ("A 1 블록", "A 1 블록"),
])
def test_complex_name_read_letter_by_letter_is_joined(cell, expected):
    # 좁은 단지 칸의 이름은 글자 사이 공백과 함께 읽힌다. 띄어 쓴 낱말은 그대로 둔다.
    cells = [row[:] for row in NONHYEON_RESERVE]
    cells[3][0] = cell
    selected = table_pages(cells, page=4)

    result = supplied(selected, {"rowIds": ["P4:T1:R4"], "complexName": cell, "housingType": "26A",
                                 "target": "대학생·청년(소득 없음)", "reserveCount": 20})

    assert result.sheet["housingTypes"][0]["complexName"] == expected
    assert [item["sourceComplexName"] for item in result.tree["data"]["complexes"]] == [expected]
    assert "housingTypes[0].complexName" not in result.unresolved()


# 학익 SK VIEW 재개발임대 공급표. 모집 열 없이 '공급 호수'가 이번 공고 모집수다.
HAGIK_SUPPLY = [
    ["주택유형", "주택형", "공급 호수", "호별 주택면적 (㎡)", None, None, None, None],
    [None, None, None, "주거전용", "주거공용", "그 밖의 공용면적", None, "계약면적"],
    [None, None, None, None, None, "기타공용", "주차장", None],
    ["국민임대 (재개발임대주택)", "38", "80", "38.9945", "13.6271", "1.2892", "22.6416", "76.5524"],
]


def test_supply_count_column_without_a_recruitment_column_is_the_recruitment_count():
    # 학익: '공급 호수' 열을 모집수 열로 읽지 못해 모델이 낸 모집수 80이 미확인이었다.
    result = supplied(table_pages(HAGIK_SUPPLY, page=1),
                      {"rowIds": ["P1:T1:R4"], "complexName": "", "housingType": "38", "target": "전체",
                       "supplyHouseholdCount": 80})

    assert readable(result.sheet)["quotas"] == [("WINNER", 80, ["38/전체"])]
    assert not [path for path in result.unresolved() if path.startswith("cells")]



def test_household_count_written_with_a_slash_is_not_confirmed_as_its_first_value():
    # 첫 값 규칙은 세부 타입 면적 칸만이다. 건설호수 '142/ 42'의 142는 확인된 건설호수가 아니다.
    cells = [row[:] for row in DANGSU_SUPPLY]
    cells[4][3] = "142/ 42"
    result = supplied(table_pages(cells, page=4),
                      {"rowIds": ["P4:T1:R5"], "complexName": "수원 당수 2단지", "housingType": "26B/C",
                       "target": "대학생, 청년, 주거급여 수급자", "constructedHouseholdCount": 142, "exclusiveArea": 26.70})

    assert result.sheet["housingTypes"][0]["constructedHouseholdCount"] is None
    assert "housingTypes[0].constructedHouseholdCount" in result.unresolved()
    assert result.sheet["housingTypes"][0]["exclusiveArea"] == 26.70


def test_income_tier_in_the_condition_prefers_the_income_marked_cell_over_a_whole_cell_of_the_same_name():
    # 공급표 대상 칸이 통째로 '청년'이고 임대조건표가 '청년(소득有)'이면, 소득有 구간 대상은 청년(소득 있음)이다.
    supply = tables.SupplyTable.of(4, 1, [["주택형", "공급대상", "모집호수"], ["14", "청년", "68"]], None, False)
    rent = tables.SupplyTable.of(5, 1, [["주택형", "공급대상", "임대보증금(천원)"], ["14", "청년(소득有)", "24,480"]], None, False)
    result = supplied(several_tables_pages(supply, rent),
                      {"rowIds": ["P4:T1:R2", "P5:T1:R2"], "housingType": "14", "target": "청년",
                       "applicationCondition": "소득有", "rentalDeposit": 24480000})

    assert [target["label"] for target in result.sheet["targets"]] == ["청년(소득 있음)"]
