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


# 임대조건표의 전환가능 보증금 한도액 칸을 PyMuPDF가 읽은 모양(행 일부만). 옆에 전환 뒤 총액인 최대전환 시 보증금 열이 있다.
SAMCHEOK_CONVERSION_CELLS = [
    ["단지명", "주 택 형", "임대조건", None, None, None, "전환가능 보증금 한도액(원)", "최대전환시 임대조건", None],
    [None, None, "임대보증금(원)", None, None, "월임대료 (원)", None, None, None],
    [None, None, None, None, None, None, None, "보증금 (원)", "월임대료 (원)"],
    [None, None, "계", "계약금", "잔금", None, None, None, None],
    ["삼척도계", "46", "24,546,000", "1,200,000", "23,346,000", "92,720", "(+) 6,000,000", "30,546,000", "62,720"],
    [None, None, None, None, None, None, "(-) 20,000,000", "4,546,000", "151,050"],
]
# 한도액 머리글이 부호 칸과 금액 칸을 함께 덮는 표(광주첨단 행복주택)
CHEOMDAN_CONVERSION_CELLS = [
    ["단지명", "공급 형별 (㎡)", "공급대상", None, "임대조건(원)", None, None, None, "전환가능 보증금 한도액 (원)", None,
     "최대전환시 임대조건(원)", None, "최대 거주 기간 (년)"],
    [None, None, None, None, "임대보증금", None, None, "월임대료", None, None, None, None, None],
    [None, None, None, None, "계", "계약금", "잔금", None, None, None, "임대보증금", "월임대료", None],
    ["첨단 H-1BL", "39(창업인)", "청년 창업인", None, "41,848,000", "2,092,400", "39,755,600", "226,670", "(+)",
     "27,000,000", "68,848,000", "91,670", "10년(무자녀) 14년(유자녀))"],
    [None, None, None, None, None, None, None, None, "( - )", "34,000,000", "7,848,000", "325,830", None],
    [None, None, "청 년", "소득(×)", "35,570,000", "1,778,500", "33,791,500", "192,670", "(+)", "23,000,000",
     "58,570,000", "77,670", "10년"],
]
# 증액·감액 조건을 따로 둔 표(충북혁신4)
CHUNGBUK_CONVERSION_CELLS = [
    ["단지명 (블록)", "주택형 (타입)", "임대조건", None, None, None, "증액 (현행 전환요율 5% 적용 시)", None, None,
     "감액 (현행 전환요율 3.5% 적용 시)", None, None],
    [None, None, "임대보증금", None, None, "월 임대료", None, None, None, None, None, None],
    [None, None, "합계", "계약금 (계약 시)", "잔금 (입주 시)", None, "한도액", "보증금", "임대료", "한도액", "보증금", "임대료"],
    ["충북혁신4 (천년나무4)", "059.9800A (59A)", "33,148,000", "6,600,000", "26,548,000", "292,150", "42,000,000",
     "75,148,000", "117,150", "-24,000,000", "9,148,000", "362,150"],
]


def test_conversion_limit_column_is_the_converted_deposit_not_the_deposit_after_maximum_conversion():
    table = tables.SupplyTable.of(2, 3, SAMCHEOK_CONVERSION_CELLS, None, False)

    assert table.columns["convertedDeposit"] == 6
    assert table.rows[4].values["convertedDeposit"] == "6,000,000"
    assert table.rows[4].values["convertedDepositUnit"] == "원"
    # 감액(-) 한도 줄은 전환보증금(증액 한도)이 아니다.
    assert "convertedDeposit" not in table.rows[5].values


@pytest.mark.parametrize(("cell", "expected"), [
    ("(+) 5,000,000 (-) 26,000,000", "5,000,000"),
    ("( ) , (+) 4,000", "4,000"),
    ("27,000,000", "27,000,000"),
    ("( ) , (- ) 6,000", None),
    ("( ) , (+) -", None),
    ("( ) 31000000\n- , ,", None),
])
def test_converted_deposit_cell_is_read_as_its_increase_limit_only(cell, expected):
    rows = [*SAMCHEOK_CONVERSION_CELLS[:4], ["삼척도계", "46", "24,546,000", "1,200,000", "23,346,000", "92,720", cell,
                                             "30,546,000", "62,720"]]

    assert tables.SupplyTable.of(2, 3, rows, None, False).rows[4].values.get("convertedDeposit") == expected


def test_conversion_limit_split_into_sign_and_amount_cells_reads_the_increase_rows():
    table = tables.SupplyTable.of(3, 2, CHEOMDAN_CONVERSION_CELLS, None, False)

    assert table.columns["convertedDeposit"] == 9
    assert [row.values.get("convertedDeposit") for row in table.rows[3:]] == ["27,000,000", None, "23,000,000"]


def test_increase_limit_column_of_a_table_split_into_increase_and_decrease_is_the_converted_deposit():
    table = tables.SupplyTable.of(4, 1, CHUNGBUK_CONVERSION_CELLS, None, False)

    assert table.columns["convertedDeposit"] == 6
    assert table.rows[3].values["convertedDeposit"] == "42,000,000"


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
    # 합계 줄 표기가 이어 붙은 머리글은 주택형 열이지만 '계약면적'처럼 '계'로 시작하는 낱말이 붙으면 아니다.
    (["공급형별총계", "공급대상", "모집호수"], 0),
    (["공급형별계약면적", "공급대상", "모집호수"], None),
])
def test_supply_type_header_is_the_housing_type_only_without_another_type_column(header, expected):
    columns = tables.SupplyTable.of(1, 1, [header, ["26", "청년", "20"]], None, False).columns

    assert columns.get("housingType") == expected


# 사천벌리1 임대조건표(2쪽)를 PyMuPDF가 읽은 모양. 신청자 묶음(가군·나군)이 금액 열을 나눠 덮는 병합 머리글이다.
SACHEON_RENT_CELLS = [
    ["공급형별 (㎡)", "가군(생계·의료급여수급자 등)", None, None, None, "나군(일반 등)", None, None, None],
    [None, "임대보증금(원)", None, None, "월 임대료 (원)", "임대보증금(원)", None, None, "월 임대료 (원)"],
    [None, "계", "계약금 (계약 시)", "잔금 (입주 시)", None, "계", "계약금 (계약 시)", "잔금 (입주 시)", None],
    ["26", "2,520,000", "126,000", "2,394,000", "50,160", "7,648,000", "382,400", "7,265,600", "115,940"],
    ["31", "2,993,000", "149,650", "2,843,350", "59,570", "8,692,000", "434,600", "8,257,400", "127,970"],
]


def test_money_columns_split_by_applicant_group_headers_are_read_per_group():
    row = tables.SupplyTable.of(2, 1, SACHEON_RENT_CELLS, None, False).rows[3]

    # 표 전체로는 보증금·월임대료 열이 둘씩이라 정하지 않고, 묶음마다 계 열(계약금·잔금 아님)과 월임대료 열을 읽는다.
    assert row.values == {"housingType": "26"}
    assert row.target_values == {
        "가군(생계·의료급여수급자 등)": {"rentalDeposit": "2,520,000", "rentalDepositUnit": "원",
                                "monthlyRent": "50,160", "monthlyRentUnit": "원"},
        "나군(일반 등)": {"rentalDeposit": "7,648,000", "rentalDepositUnit": "원",
                     "monthlyRent": "115,940", "monthlyRentUnit": "원"},
    }


@pytest.mark.parametrize("rows", [
    SACHEON_SUPPLY_CELLS,
    # 기본·최대 전환 조건은 신청자 묶음이 아니다. 두 칸이 덮는 금액 필드가 다르다.
    [["주택형", "기본 임대조건", None, "최대 전환 시", None],
     [None, "임대보증금", "월임대료", "임대보증금", "월임대료"],
     ["26A", "10,000,000", "100,000", "20,000,000", "50,000"]],
    # 금액 머리글 자체가 병합된 표(임대보증금 > 계·계약금·잔금)도 묶음이 아니다.
    [["주택형", "임대보증금", None, None, "월임대료"],
     [None, "계", "계약금", "잔금", None],
     ["26A", "10,000,000", "500,000", "9,500,000", "100,000"]],
])
def test_tables_without_applicant_group_headers_have_no_group_values(rows):
    assert all(not row.target_values for row in tables.SupplyTable.of(1, 1, rows, None, False).rows)


def test_block_column_is_the_complex_name():
    # 화성동탄2 리츠 공급표는 단지명 대신 첫 열 머리글이 '블록'이고 값이 '동탄2 A40BL'이다.
    rows = [
        ["블록", "최초 입주지정기간 종료일", "주택형", "세대당 주택면적(㎡)", None, "건설호수", "모집자수"],
        [None, None, None, "주거전용", "주거공용", None, None],
        ["동탄2 A40BL", "2017.04.14", "074.9900A", "74.99", "23.6028", "272", "2"],
    ]

    values = tables.SupplyTable.of(4, 2, rows, None, False).rows[2].values

    assert values["complexName"] == "동탄2 A40BL"
    assert values["housingType"] == "074.9900A"


def test_supply_type_header_followed_by_the_total_row_label_is_the_housing_type():
    # 대구연호 통합공공임대: 머리글 '공급형별' 아래 첫 줄이 '총계'라 머리글이 "공급형별총계"로 이어진다.
    rows = [
        ["공급형별", "세대당 계약면적(㎡)", None, "공급호수"],
        [None, "주거전용", "주거공용", None],
        ["총계", None, None, "522"],
        ["31A", "31.86", "12.10", "262"],
    ]

    values = tables.SupplyTable.of(4, 1, rows, None, False).rows[3].values

    assert values["housingType"] == "31A"


def test_merged_complex_cell_names_every_row_it_spans():
    # 화성동탄2 리츠: 블록 칸이 세로로 병합돼 아래 행은 칸이 비어(None) 있다. 병합 칸은 위 행 값과 같은 칸이다.
    rows = [
        ["블록", "주택형", "주거전용", "건설호수"],
        ["동탄2 A40BL", "074.9900A", "74.99", "272"],
        [None, "084.9600A", "84.96", "300"],
        ["동탄2 A50BL", "051.9900A", "51.99", "238"],
        ["", "059.6900A", "59.69", "278"],
    ]

    table = tables.SupplyTable.of(4, 2, rows, None, False)

    assert [row.values.get("complexName") for row in table.rows[1:]] == ["동탄2 A40BL", "동탄2 A40BL", "동탄2 A50BL", None]


def test_supply_area_parent_header_over_exclusive_and_common_columns_is_not_the_supply_area():
    rows = [
        ["블록", "주택형", "세대당 주택면적(㎡)", None, "건설호수"],
        [None, None, "공급면적", None, None],
        [None, None, "주거전용", "주거공용", None],
        ["동탄2 A40BL", "074.9900A", "74.99", "23.6028", "272"],
    ]

    table = tables.SupplyTable.of(4, 2, rows, None, False)

    assert table.rows[3].values["exclusiveArea"] == "74.99"
    assert "supplyArea" not in table.rows[3].values


def test_application_category_column_of_a_permanent_rental_rent_table_is_the_target():
    # 홍성 영구임대: 임대조건표의 대상 열 머리글이 '적용구분'이고 값이 「가」군·「나」군이다.
    rows = [
        ["단지명", "주택형", "적용구분", "임대보증금", "월임대료"],
        ["도청이전신도시 RH10-2BL", "26", "「가」군", "2,669,000", "53,120"],
    ]

    assert tables.SupplyTable.of(1, 2, rows, None, False).rows[1].values["target"] == "「가」군"


# 대구 달서·달성 10년 공공임대 리츠: 주택형(신청형) 칸이 주택타입 행들에 걸쳐 세로 병합되고, 병합된 면적 칸은 None이다.
HOUSE_TYPE_ROWS = [
    ["주택형 (신청형)", "주택 타입", "세대당 주택면적(㎡)", None, "금회 예비입주자 모집 호수"],
    [None, None, "주거 전용", "주거 공용", None],
    ["합 계", None, None, None, "250"],
    ["049.7600A", "49A", "49.76", "17.8623", "35"],
    ["059.9000A", "59A", "59.98", "21.5309", "125"],
    [None, "59A1", None, None, None],
    [None, "59C", None, None, None],
    # 면적 행과 이름 행이 어긋나 이름 없이 면적만 있는 행. 아래 주택타입들이 이 면적을 이어받는다.
    [None, None, "59.99", "21.5345", None],
    [None, "59A2", None, None, None],
    [None, "59D", None, None, None],
    ["059.9000B", "59B", "59.93", "21.5130", "25"],
    [None, "59B1", "59.92", "21.5094", None],
    ["084.0000A", "84A", "84.67", "30.3939", "35"],
    [None, "84B", None, None, None],
    [None, "84A1", "84.96", "30.4980", None],
]


def subtypes_by_housing_type(table):
    return {row.values["housingType"]: [(item.name, item.exclusive_area, item.common_area) for item in row.subtypes]
            for row in table.rows if row.subtypes}


def test_house_type_rows_under_a_merged_housing_type_cell_become_one_subtype_per_exclusive_area():
    table = tables.SupplyTable.of(3, 1, HOUSE_TYPE_ROWS, None, False)

    assert subtypes_by_housing_type(table) == {
        "049.7600A": [("49A", 49.76, 17.8623)],
        "059.9000A": [("59A·59A1·59C", 59.98, 21.5309), ("59A2·59D", 59.99, 21.5345)],
        "059.9000B": [("59B", 59.93, 21.513), ("59B1", 59.92, 21.5094)],
        "084.0000A": [("84A·84B", 84.67, 30.3939), ("84A1", 84.96, 30.498)],
    }
    # 공급행 값은 지금처럼 신청형 행의 칸이다(첫 면적).
    assert table.rows[4].values["exclusiveArea"] == "59.98"


def test_subtype_common_and_supply_areas_come_from_its_area_rows_only_when_they_agree():
    rows = [
        ["주택형", "주택타입", "주거전용", "주거공용", "공급면적", "예비자"],
        ["059.9000A", "59A", "59.98", "21.5309", "81.5109", "10"],
        [None, "59A1", "59.98", "21.5400", "81.5200", None],
        [None, "59C", None, None, None, None],
        ["074.9700A", "74A", "74.97", "26.9119", "101.8819", "5"],
    ]

    table = tables.SupplyTable.of(3, 1, rows, None, False)

    assert table.rows[1].subtypes == (tables.TableSubtype("59A·59A1·59C", 59.98, None, None),)
    assert table.rows[4].subtypes == (tables.TableSubtype("74A", 74.97, 26.9119, 101.8819),)


def test_housing_type_with_a_house_type_whose_area_is_blank_has_no_subtypes():
    rows = [
        ["주택형", "주택타입", "주거전용", "예비자"],
        ["059.9000A", "59A", "59.98", "10"],
        [None, "59A1", "", None],
        ["074.9700A", "74A", "74.97", "5"],
    ]

    table = tables.SupplyTable.of(3, 1, rows, None, False)

    assert [row.subtypes for row in table.rows] == [(), (), (), (tables.TableSubtype("74A", 74.97, None, None),)]


def test_table_without_a_house_type_column_has_no_subtypes():
    rows = [
        ["주택형", "주거전용", "주거공용", "예비자"],
        ["059.9000A", "59.98", "21.5309", "10"],
        [None, "59.99", "21.5345", None],
    ]

    table = tables.SupplyTable.of(3, 1, rows, None, False)

    assert "houseType" not in table.columns
    assert all(row.subtypes == () for row in table.rows)


def test_single_cell_entrant_recruitment_header_is_the_recruitment_column():
    from ai_worker.tables import _table_field_columns
    # 한 칸짜리 "입주자 모집호수"는 모집호수 열이고, 모집호수 아래로 나뉜 입주자 하위 열만 당첨자 열이다.
    assert _table_field_columns([["단지명", "주택형", "입주자 모집호수"], ["A", "59", "10"]])["totalSupplyHouseholdCount"] == 2
    split = _table_field_columns([["단지명", "주택형", "모집호수", None, None], [None, None, "계", "입주자", "예비자"],
                                  ["A", "59", "13", "0", "13"]])
    assert (split["totalSupplyHouseholdCount"], split["supplyHouseholdCount"], split["reserveCount"]) == (2, 3, 4)
