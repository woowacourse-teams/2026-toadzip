from types import SimpleNamespace

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
