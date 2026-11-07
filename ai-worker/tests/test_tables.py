from types import SimpleNamespace

import pytest

from ai_worker import tables
from conftest import RENT_CELLS


def test_split_table_headers_map_reserve_count_to_its_own_column():
    rows = [
        ["단지명", "공급형", None, "공급 대상", "모집 호수", "세대 당 계약면적(㎡)", None],
        [None, "주택 구분", "청약 구분", None, "예비자", "주거 전용", "주거 공용"],
        [None, None, None, None, "일반 공급", None, None],
        ["Test", "26", "26A", "청년", "20", "26.0000", "15.0000"],
    ]

    values = tables.SupplyTable.of(4, 1, rows, None, False).rows[3].values

    assert values == {"complexName": "Test", "housingType": "26A", "target": "청년", "reserveCount": "20",
                      "exclusiveArea": "26.0000", "commonArea": "15.0000"}


def test_table_header_mapping_distinguishes_base_rent_from_conversion_options():
    rows = [
        ["공급형", "공급대상", "임대조건", None, None, None, "임대보증금 최소화", "", "임대보증금 최대화", ""],
        ["", "", "임대보증금", "", "", "월 임대료", "임대보증금", "월임대료", "임대보증금", "월임대료"],
        ["39", "대학생", "계", "계약금", "잔금", None, None, None, None, None],
        [None, None, "56,780,000", "2,839,000", "53,941,000", "260,240", "9,780,000", "397,320",
         "87,780,000", "105,240"],
    ]

    columns = tables.SupplyTable.of(4, 1, rows, None, False).columns

    assert columns["rentalDeposit"] == 2
    assert columns["monthlyRent"] == 5
    assert columns["convertedDeposit"] == 8


def test_continued_table_page_reuses_the_previous_page_header_columns():
    headers = [
        ["단지명", "공급형", None, "공급 대상", "모집 호수", "세대 당 계약면적(㎡)", None],
        [None, "주택 구분", "청약 구분", None, "예비자", "주거 전용", "주거 공용"],
        [None, None, None, None, "일반 공급", None, None],
    ]
    continuation_rows = [["Test", "26", "26A", "청년", "20", "26.0000", "15.0000"]]

    inherited = tables.SupplyTable.of(4, 1, headers, None, False).columns
    values = tables.SupplyTable.of(5, 1, continuation_rows, inherited, False).rows[0].values

    assert values == {"complexName": "Test", "housingType": "26A", "target": "청년", "reserveCount": "20",
                      "exclusiveArea": "26.0000", "commonArea": "15.0000"}


def found_on_page(monkeypatch, *cells, page_number=1, previous=None):
    """표 인식기가 그 쪽에서 cells 표들을 찾은 것처럼 해 쪽 표 읽기 결과를 돌려준다."""
    monkeypatch.setattr(tables, "detected_tables",
                        lambda page: ([tables.DetectedTable(table, (0, 0, 600, 800)) for table in cells], False))
    return tables.page_tables(SimpleNamespace(), page_number, previous)


def test_counts_rental_rows_by_housing_type_and_target_filling_merged_cells(monkeypatch):
    assert found_on_page(monkeypatch, RENT_CELLS).rental_keys == {
        ("16A", "대학생·청년(소득無)"), ("16A", "청년(소득有)"), ("16A", "기존거주자(철거민)"), ("21A", "기존거주자(철거민)"),
    }


def test_tables_without_rent_header_are_not_counted(monkeypatch):
    assert found_on_page(monkeypatch, [["형별", "공급호수"], ["16A", "78", "31786000"]]).rental_keys == set()


def test_continued_table_rows_without_housing_type_are_not_counted(monkeypatch):
    continued = [RENT_CELLS[0], RENT_CELLS[1], [None, "고령자", "48000000\n, ,", None, None, "210000\n,", None]]

    assert found_on_page(monkeypatch, continued).rental_keys == set()


def test_complex_location_column_is_mapped_as_address_but_reception_location_is_not():
    located = [["단지명", "주택형", "공급대상", "모집호수", "단지 소재지"], ["Test", "26A", "청년", "20", "서울특별시 중구 세종대로 110"]]
    reception = [["단지명", "주택형", "공급대상", "모집호수", "접수처 위치"], ["Test", "26A", "청년", "20", "서울특별시 강남구 테헤란로 1"]]

    assert tables.SupplyTable.of(4, 1, located, None, False).columns["address"] == 4
    assert "address" not in tables.SupplyTable.of(4, 1, reception, None, False).columns


def test_complex_name_column_that_also_names_the_location_is_not_used_as_address():
    rows = [["단지명(소재지)", "주택형", "공급대상", "모집호수"], ["Test(서울특별시 중구 세종대로 110)", "26A", "청년", "20"]]

    assert "address" not in tables.SupplyTable.of(4, 1, rows, None, False).columns


# 사천벌리1 영구임대 예비입주자 공고 공급표를 PyMuPDF가 읽은 모양 그대로 옮겼다. 병합 칸은 None이다.
SACHEON_SUPPLY_CELLS = [
    ["공급 형별 (㎡)", "건설 세대수 (호)", "세대별 계약면적(㎡)", None, None, None, None, "현재 공가 호수", "금회 모집 예비자", "해당동",
     "구조 및 난방"],
    [None, None, "전용", "주거 공용", "그 밖의 공용면적", None, "합계", None, None, None, None],
    [None, None, None, None, "기타 공용", "주차장", None, None, None, None, None],
    ["26", "298", "26.37", "10.76", "-", "-", "37.13", "77", "110", "101,102", "복도식 개별난방"],
    ["31", "149", "31.32", "10.87", "-", "-", "42.19", "9", "30", "103", None],
]


def test_supply_type_column_and_exclusive_sub_header_under_contract_area_are_read():
    table = tables.SupplyTable.of(1, 1, SACHEON_SUPPLY_CELLS, None, False)

    assert {name: table.columns.get(name) for name in ("housingType", "exclusiveArea", "commonArea")} == {
        "housingType": 0, "exclusiveArea": 2, "commonArea": 3}
    assert table.rows[3].values == {"housingType": "26", "constructedHouseholdCount": "298", "exclusiveArea": "26.37",
                                    "commonArea": "10.76", "reserveCount": "110"}


def test_exclusive_sub_header_under_a_merged_residential_parent_is_read_but_common_columns_are_not():
    rows = [["주택형", "주거", None, "기타 공용", "모집호수"], [None, "전용", "공용", None, None], ["26A", "26.37", "10.76", "3.1", "20"]]

    columns = tables.SupplyTable.of(1, 1, rows, None, False).columns

    assert (columns["housingType"], columns["exclusiveArea"], columns["commonArea"]) == (0, 1, 2)


def test_common_area_sub_header_that_mentions_exclusive_is_not_the_exclusive_area():
    rows = [["주택형", "계약면적", None, "모집호수"], [None, "전용", "공용 (전용 외 면적)", None], ["26A", "26.37", "10.76", "20"]]

    assert tables.SupplyTable.of(1, 1, rows, None, False).columns["exclusiveArea"] == 1


@pytest.mark.parametrize(("header", "expected"), [
    (["공급형", "공급대상", "모집호수"], 0),
    (["공급\n형별", "공급대상", "모집호수"], 0),
    # 다른 주택형 열이 있으면 "공급형"은 보지 않는다. 뒤에 다른 낱말이 붙은 머리글은 주택형 열이 아니다.
    (["공급형", "주택형", "모집호수"], 1),
    (["공급형별 임대보증금", "공급대상", "모집호수"], None),
])
def test_supply_type_header_is_the_housing_type_only_without_another_type_column(header, expected):
    columns = tables.SupplyTable.of(1, 1, [header, ["26", "청년", "20"]], None, False).columns

    assert columns.get("housingType") == expected
