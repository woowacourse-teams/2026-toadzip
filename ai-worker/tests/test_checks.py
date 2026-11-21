import json
from dataclasses import dataclass
from decimal import Decimal

import pytest

from ai_worker import checks
from ai_worker.extraction import PermanentError
from ai_worker.source_frame import SourceSupplyRow
from ai_worker.supply_sheet import Cite, Entry, Shared, SupplySheet, Unit


def row(housing_type="26A", common_area=13.5, supply_area=None, move_in=None, target="일반", count=None, area=None,
        complex_name="여수미평1", subtypes=()):
    """공급표 모집 단위 하나. subtypes는 (주택타입 이름, 전용면적)이다."""
    return Unit(complex_name, housing_type,
                {"expectedMoveInMonth": move_in, "exclusiveArea": area, "commonArea": common_area,
                 "supplyArea": supply_area, "totalSupplyHouseholdCount": count},
                entries=[Entry(target, {})],
                house_types=[{"name": name, "exclusiveArea": value} for name, value in subtypes])


def lh(type_name="26", area="", count="", complex_label="여수미평1"):
    return SourceSupplyRow("LH", None, complex_label, type_name, area, "", count)


@dataclass
class Case:
    tree: dict
    sheet: SupplySheet


def response(rows=None, evidence=(), cites=(), shared=(), announcement=None, complexes=None) -> Case:
    """검산할 결과. cites는 모집 단위 근거 (단위 순번, 쪽, 인용), shared는 공통 수량 (단위 순번들, 수, 쪽, 인용)이다."""
    units = rows if rows is not None else [row(target="가군(생계·의료급여수급자 등)")]
    tree = {
        "data": {
            "announcement": announcement or {"name": "여수미평1 영구임대주택 예비입주자 모집", "postedDate": "2026-10-20",
                                             "applicationStartDate": "2026-11-10",
                                             "applicationEndDate": "2026-11-12", "winnerAnnouncementDate": None},
            "complexes": complexes or [{"sourceComplexName": "여수미평1"}],
            "schedules": [],
        },
        "notApplicable": [],
        "evidence": list(evidence),
    }
    return Case(tree, SupplySheet(
        units=units,
        shared=[Shared("WINNER", count, [units[at] for at in owners], page, quote) for owners, count, page, quote in shared],
        evidence=[Cite(units[at], "", page, quote) for at, page, quote in cites]))


def review(case, pages=(1, 2, 10), texts=None, lh_rows=(), rental_rows=0):
    texts = texts if texts is not None else [""] * 10
    return checks.review(case.tree, case.sheet, list(pages), texts, list(lh_rows), rental_rows)


def test_remaps_evidence_pages_to_original_pdf_and_keeps_supply_pages():
    # 본문 근거는 첨부 쪽 번호라 원본 쪽으로 바꾼다. 공급표 근거는 원문 행 ID에서 원본 쪽을 복원했으므로 그대로다.
    case = response(
        shared=[([0], 20, 3, "20")],
        evidence=[{"path": "announcement.name", "page": 1, "quote": "모집"},
                  {"path": "schedules[0]", "page": 3, "quote": "‘26.11.10"}],
    )
    texts = ["예비입주자 모집", "공급 20호"] + [""] * 7 + ["신청기간 ‘26. 11. 10"]

    assert review(case, texts=texts) == []
    assert case.tree["evidence"][1]["page"] == 10
    assert case.sheet.shared[0].page == 3


def test_out_of_range_page_is_left_as_is_with_warning():
    case = response(evidence=[{"path": "receptionPlaces[1]", "page": 7, "quote": "x"}], cites=[(0, 11, "x")],
                    shared=[([0], 20, 0, "20")])

    assert review(case) == [
        "여수미평1 26A: 첨부한 쪽 범위를 벗어난 근거 쪽 번호(11)",
        "공통 수량(여수미평1 26A): 첨부한 쪽 범위를 벗어난 근거 쪽 번호(0)",
        "receptionPlaces[1]: 첨부한 쪽 범위를 벗어난 근거 쪽 번호(7)",
    ]
    assert case.tree["evidence"][0]["page"] == 7
    assert case.sheet.evidence[0].page == 11


def test_warns_rules_the_model_often_breaks():
    case = response(
        announcement={"name": "사천벌리1 영구임대주택 예비입주자 모집", "postedDate": "2026-10-16",
                      "applicationStartDate": "2026-10-28T09:00:00", "applicationEndDate": "2026-10-30",
                      "winnerAnnouncementDate": "2027-01-22"},
        complexes=[{"sourceComplexName": "사천벌리1 영구임대주택 예비입주자 모집"}],
        rows=[row("26.37", 13.5, 39.87, "2025-06", "「가」군", complex_name="사천벌리1")],
    )

    assert review(case, pages=[1]) == [
        "announcement.applicationStartDate: 날짜 형식이 아님(2026-10-28T09:00:00)",
        "complexes[0].sourceComplexName: 단지명이 공고 제목 전체와 같음",
        "사천벌리1 26.37 주택형 이름: 면적 숫자만 있음(26.37)",
        "사천벌리1 26.37 공급면적: 공용면적이 있는데 공급면적도 채워짐",
        "사천벌리1 26.37 입주 예정 연월: 공고일보다 이른 연월(2025-06)",
        "사천벌리1 26.37 공급대상: 표기 규칙 위반(「가」군)",
    ]


def test_warns_quote_words_missing_from_its_original_page():
    case = response(cites=[(0, 3, "26A 13.50 20")],
                    evidence=[{"path": "announcement.name", "page": 1, "quote": "여수미평1 모집"}])
    texts = ["여수미평1  모집", "", "주택형 26A 공용 13.50 모집호수 19"] + [""] * 7

    # 본문 첨부 1쪽은 원본 1쪽이다. 공급표 근거는 원본 쪽에서 낱말마다 찾는다.
    assert review(case, pages=[1, 3], texts=texts) == ["여수미평1 26A: 근거 인용문의 20이(가) 3쪽 원문에 없음"]


def test_quote_differing_from_its_page_only_in_quotation_mark_shape_is_not_warned():
    case = response(cites=[(0, 3, "26A 13.50 19")],
                    evidence=[{"path": "announcement.applicationEndDate", "page": 1, "quote": "'26.09.28.(월)"}])
    texts = ["접수 ’26.09.28.(월)", "", "주택형 26A 공용 13.50 모집호수 19"] + [""] * 7

    assert review(case, pages=[1, 3], texts=texts) == []


def test_quote_of_table_row_matches_cells_extracted_in_other_order():
    case = response(cites=[(0, 1, "경암부향 39 39.95 717")])
    texts = ["39.95\n경암부향\n717 39"]

    assert review(case, pages=[1], texts=texts) == []


@pytest.mark.parametrize(("value", "expected"), [
    # 26.375·84.9600은 백엔드 HousingTypeAreas 단위 테스트와 같은 경계값이다. 26.125·26.365는 round()·float 오차로 틀어지는 값이다.
    (26.375, Decimal("26.38")), ("26.375", Decimal("26.38")), ("84.9600", Decimal("84.96")), (84.96, Decimal("84.96")),
    (26.125, Decimal("26.13")), (26.365, Decimal("26.37")),
    (26.3749, Decimal("26.37")), (59, Decimal("59.00")), ("", None), ("면적", None), (None, None), (True, None),
])
def test_area_key_rounds_half_up_at_two_decimals(value, expected):
    assert checks.area_key(value) == expected


def test_several_lh_rows_of_one_area_attach_to_one_pdf_row_and_their_sum_is_compared():
    # 사천벌리1처럼 LH가 같은 주택형을 신청자 구분(우선·일반)으로 나눠 보내도 PDF 주택형은 하나다. 줄 수가 달라도 오류가 아니다.
    lh_rows = [lh("26(우선)", "26.37", "60"), lh("26(일반)", "26.3700", "50"), lh("31", "31.32", "30")]
    rows = [row("26", area=26.37, count=110), row("31", area=31.32, count=30)]

    assert review(response(rows=rows), lh_rows=lh_rows) == []
    rows[0].values["totalSupplyHouseholdCount"] = 100
    assert review(response(rows=rows), lh_rows=lh_rows) == [
        "여수미평1 26 모집수: 같은 전용면적(26.37) LH API 공급행 2줄의 모집수 합계 110와 다름(100)",
    ]


def test_pdf_rows_of_one_area_with_different_type_names_are_compared_together_with_the_lh_rows_of_that_area():
    # 같은 면적에 형명만 다른 PDF 주택형(26A 10 / 26B 5)은 화면이 하나로 접는다. 주택형마다 LH 합계와 맞대면 거짓 경고가 난다.
    lh_rows = [lh("26A", "26.01", "10"), lh("26B", "26.01", "5")]
    rows = [row("26A", area=26.01, count=10), row("26B", area=26.01, count=5)]

    assert review(response(rows=rows), lh_rows=lh_rows) == []
    rows[1].values["totalSupplyHouseholdCount"] = 4
    assert review(response(rows=rows), lh_rows=lh_rows) == [
        "여수미평1 26A 모집수: 같은 전용면적(26.01) LH API 공급행 2줄의 모집수 합계 15와 "
        "같은 면적 PDF 주택형 2개(여수미평1 26A, 여수미평1 26B)의 합 14가 다름",
    ]


def test_lh_area_is_compared_at_two_decimals_rounded_half_up():
    lh_rows = [lh("26", "26.375", "10"), lh("84", "84.9600", "5")]
    rows = [row("26", area=26.38, count=11), row("84", area=84.96, count=6)]

    assert review(response(rows=rows), lh_rows=lh_rows) == [
        "여수미평1 26 모집수: 같은 전용면적(26.38) LH API 공급행 1줄의 모집수 합계 10와 다름(11)",
        "여수미평1 84 모집수: 같은 전용면적(84.96) LH API 공급행 1줄의 모집수 합계 5와 다름(6)",
    ]


def test_subtype_areas_of_a_pdf_row_collect_lh_rows_of_each_area():
    # 공급 표가 두 주택형을 모집수 하나로 안내하면 한 모집 단위의 주택타입 면적마다 LH 행이 붙는다.
    shared = row("51A/B", count=240, subtypes=[("51A", 51.98), ("51B", 51.91)])
    lh_rows = [lh("51A", "51.98", "200"), lh("51B", "51.91", "40")]

    assert review(response(rows=[shared]), lh_rows=lh_rows) == []
    assert review(response(rows=[shared]), lh_rows=lh_rows[:1]) == [
        "여수미평1 51A/B 모집수: 같은 전용면적(51.98, 51.91) LH API 공급행 1줄의 모집수 합계 200와 다름(240)",
    ]


def test_lh_rows_or_pdf_rows_without_a_comparable_value_are_skipped_silently():
    # 면적 없는 LH 행, 모르는 LH 모집수(0·빈 값), 비어 있는 PDF 모집수(공통 모집수), 붙는 LH 행이 없는 PDF 행, PDF 행이 없는 LH 행
    lh_rows = [lh("39", "", "20"), lh("46", "46.1", "0"), lh("51", "51.2", "8"), lh("59", "59.3", "10"), lh("74", "74.0", "3")]
    rows = [row("39", count=7), row("46", area=46.1, count=7), row("51", area=51.2, count=None),
            row("84", area=84.0, count=1), row("59", area=59.3, count=10)]

    assert review(response(rows=rows), lh_rows=lh_rows) == []


def test_lh_complex_is_matched_to_the_pdf_complex_name_it_contains():
    lh_rows = [lh("26", "26.37", "10", "인천부평(도시재생뉴딜) 행복주택"), lh("26", "26.37", "20", "인천가정 행복주택")]
    rows = [row("26", area=26.37, count=10, complex_name="인천부평"), row("26", area=26.37, count=21, complex_name="인천가정"),
            row("26", area=26.37, count=99, complex_name="모르는 단지")]

    assert review(response(rows=rows), lh_rows=lh_rows) == [
        "인천가정 26 모집수: 같은 전용면적(26.37) LH API 공급행 1줄의 모집수 합계 20와 다름(21)",
    ]


def test_one_lh_complex_and_one_pdf_complex_are_the_same_complex_even_if_named_differently():
    assert review(response(rows=[row("26", area=26.37, count=9, complex_name="사천벌리1")]),
                  lh_rows=[lh("26", "26.37", "10", "사천벌리 영구임대")]) == [
        "사천벌리1 26 모집수: 같은 전용면적(26.37) LH API 공급행 1줄의 모집수 합계 10와 다름(9)",
    ]


def test_numeric_housing_type_copied_from_lh_api_is_not_warned():
    case = response(rows=[row(housing_type="39"), row(housing_type="26.37")])

    assert review(case, lh_rows=[lh("39"), lh("51")]) == [
        "여수미평1 26.37 주택형 이름: 면적 숫자만 있음(26.37)",
    ]


def test_integer_housing_type_from_pdf_is_not_area_even_if_lh_names_it_differently():
    # 공급 표가 "24", LH API가 "24A"인 공고. 정수는 흔한 주택형 이름이라 면적으로 경고하지 않는다.
    case = response(rows=[row(housing_type="24")])

    assert review(case, lh_rows=[lh("24A")]) == []


def test_lh_housing_type_name_missing_from_row_quote_is_warned():
    # 모델이 PDF 공급 표의 "24" 대신 틀로 받은 LH 이름 "24A"를 옮긴 경우. 근거 인용에 그 이름이 있으면 경고하지 않는다.
    case = response(rows=[row(housing_type="24A"), row(housing_type="26")],
                    cites=[(0, 1, "24 24.7200 12.6758 50"), (1, 1, "26 26.8300 13.7 40")])

    warnings = review(case, texts=["24 24.7200 12.6758 50 26 26.8300 13.7 40"] + [""] * 9, lh_rows=[lh("24A"), lh("26")])

    assert warnings == ["여수미평1 24A 주택형 이름: 근거 인용에 없는 LH 주택형 이름(24A)"]


def test_lh_housing_type_target_parentheses_need_not_be_in_quote():
    # 공급대상 괄호는 병합 셀이라 표 한 줄 인용에 없을 수 있다. 괄호 앞 표기(16A)가 있으면 경고하지 않는다.
    case = response(rows=[row(housing_type="16A(대학생/청년)")], cites=[(0, 1, "16A 16.37 27.6384 78")])

    assert review(case, texts=["16A 16.37 27.6384 78"] + [""] * 9, lh_rows=[lh("16A(대학생/청년)")]) == []


def test_housing_type_not_from_lh_is_not_checked_against_quote():
    # LH 이름이 아니면(모델이 공급 표에서 읽은 표기) 인용에 없어도 이 경고는 내지 않는다. 화면이 근거와 대조한다.
    case = response(rows=[row(housing_type="26B")], cites=[(0, 1, "26 26.10 40")])

    assert review(case, texts=["26 26.10 40"] + [""] * 9, lh_rows=[lh("26A")]) == []


def test_without_lh_rows_counts_are_not_compared():
    assert review(response(rows=[row(count=20)])) == []


def test_fewer_targets_than_rental_rows_is_warned():
    case = response(rows=[row(target="대학생"), row(target="청년")])

    assert review(case, rental_rows=3) == ["공급표: 공급대상 2개가 임대조건표 3줄보다 적어 빠진 대상이 있을 수 있음"]
    assert review(response(rows=[row(target="대학생"), row(target="청년")]), rental_rows=2) == []


def test_malformed_items_do_not_stop_review():
    case = response(evidence=[{"path": "a", "page": "2"}, "evidence", {"path": "b"}])
    case.tree["data"]["complexes"] = "complexes"

    assert review(case) == ["a: 첨부한 쪽 범위를 벗어난 근거 쪽 번호(2)", "b: 첨부한 쪽 범위를 벗어난 근거 쪽 번호(None)"]


def test_reads_first_json_object_and_ignores_trailing_text():
    tree = response().tree

    assert checks.first_json_object("\n" + json.dumps(tree) + "\n{\"extra\": 1} 끝") == tree


@pytest.mark.parametrize("reply", [
    "```json", "{\"data\": ", "[1, 2]", "{\"result\": {}}", "{\"data\": []}", "{\"data\": {\"count\": NaN}}",
])
def test_reply_without_extraction_object_is_permanent_error(reply):
    with pytest.raises(PermanentError) as error:
        checks.first_json_object(reply)

    assert error.value.code == checks.INVALID_CODE


@pytest.mark.parametrize("field, value", [
    ("announcement", None), ("announcement", []), ("schedules", None), ("schedules", "없음"),
])
def test_reply_with_wrong_data_shape_is_permanent_error(field, value):
    tree = response().tree
    if value is None:
        del tree["data"][field]
    else:
        tree["data"][field] = value

    with pytest.raises(PermanentError) as error:
        checks.first_json_object(json.dumps(tree, ensure_ascii=False))

    assert error.value.code == checks.INVALID_CODE


def test_note_paths_are_made_readable_by_the_admin_screen():
    tree = {"data": {"unresolvedFields": [
        {"path": "complexes[].parkingCount", "reason": "a"},
        {"path": "data.complexes[0].주차대수", "reason": "b"},
        {"path": "schedules[1]", "reason": "c"},
        {"path": "!!!", "reason": "d"},
    ]}, "notApplicable": [{"path": "$.receptionPlaces[0].address", "reason": "e"},
                          {"path": "receptionPlaces[1].주소", "reason": "f"}, {"path": None, "reason": "g"}]}
    warnings = []

    checks.normalize_note_paths(tree, warnings)

    # 미확인은 범위를 넓혀도 보수적이라 앞부분까지 줄이고 경고한다.
    assert [item["path"] for item in tree["data"]["unresolvedFields"]] == [
        "complexes[*].parkingCount", "complexes[0]", "schedules[1]"]
    # 해당 없음을 상위로 넓히면 그 아래 값이 모두 반영에서 빠지므로 줄이지 않고 뺀다.
    assert [item["path"] for item in tree["notApplicable"]] == ["receptionPlaces[0].address"]
    assert warnings == [
        "unresolvedFields: 읽을 수 없는 경로 data.complexes[0].주차대수를 complexes[0]로 넓혔습니다.",
        "unresolvedFields: 경로를 읽을 수 없는 항목을 뺐습니다(!!!: d)",
        "notApplicable: 경로를 읽을 수 없는 항목을 뺐습니다(receptionPlaces[1].주소: f)",
        "notApplicable: 경로를 읽을 수 없는 항목을 뺐습니다(None: g)",
    ]


def sheet(*types):
    """공급표 주택형 목록. 항목은 (이름, 전용면적, [(주택타입 이름, 전용면적)])다."""
    return {"housingTypes": [{"name": name, "exclusiveArea": area,
                              "houseTypes": [{"name": house, "exclusiveArea": house_area} for house, house_area in houses]}
                             for name, area, houses in types]}


def cells(*rows):
    """원문 표 행 ID마다 읽은 칸. 항목은 (행 ID, 주택형 칸, 주택타입 칸)이다."""
    return {row_id: {"housingType": housing, **({"houseType": house} if house is not None else {})}
            for row_id, housing, house in rows}


def test_a_pdf_housing_type_left_out_of_the_sheet_is_warned_once():
    # 저장은 PDF에 없는 주택형을 지우므로, 모델이 공급표의 형 하나를 빠뜨리면 관리자가 저장 전에 알아야 한다.
    warnings = checks.missing_housing_types(
        sheet(("16A", 16.37, [])),
        cells(("P2:T1:R2", "16A", None), ("P2:T1:R3", "31A", None), ("P2:T1:R4", "31A", None)), [])

    assert warnings == ["원본 2쪽 공급표의 주택형 31A이(가) 추출 결과에 없어 "
                        "저장하면 지금 공급표의 그 주택형이 지워질 수 있음"]


def test_a_housing_type_whose_name_was_not_confirmed_counts_as_missing():
    # 원문에서 확인하지 못한 이름은 null로 나간다. 그 형은 저장에서 지금 공급표의 형과 짝지어지지 않는다.
    assert checks.missing_housing_types(sheet((None, None, [])), cells(("P3:T1:R2", "16A", None)), []) == [
        "원본 3쪽 공급표의 주택형 16A이(가) 추출 결과에 없어 저장하면 지금 공급표의 그 주택형이 지워질 수 있음"]


@pytest.mark.parametrize("extracted", [
    sheet(("84", 84.9, [])),  # 주택형 단위로 뽑았다
    sheet(("84A", 84.9, []), ("84B", 84.8, [])),  # 주택타입 단위로 뽑았다
    sheet(("84", None, [("84A", 84.9), ("84B", 84.8)])),
], ids=["housing-type", "house-types", "house-type-list"])
def test_a_merged_housing_type_cell_is_found_by_either_column(extracted):
    # 주택형 칸이 세로로 병합되면 아래 행은 주택형 칸이 비고 주택타입 칸만 있다. 윗행 주택형을 잇는다.
    rows = cells(("P4:T1:R2", "84", "84A"), ("P4:T1:R3", "", "84B"))

    assert checks.missing_housing_types(extracted, rows, []) == []


def test_an_applicant_mark_wrapped_into_the_next_row_is_not_a_missing_type():
    # 공급대상 열이 없는 표에서 '29 (주거약자)'의 표기가 줄바꿈되어 다음 행 칸에 붙는다. 신청자 표기는 공급대상이 되어 결과 주택형은 29·37이다.
    rows = cells(("P4:T1:R2", "29", None), ("P4:T1:R3", "29 (주거약자)", None), ("P4:T1:R4", "(주거약자) 37", None))

    assert checks.missing_housing_types(sheet(("29", 29.39, []), ("37", 37.28, [])), rows, []) == []
    assert checks.missing_housing_types(sheet(("29", 29.39, [])), rows, []) == [
        "원본 4쪽 공급표의 주택형 (주거약자) 37이(가) 추출 결과에 없어 저장하면 지금 공급표의 그 주택형이 지워질 수 있음"]


def test_a_missing_house_type_row_names_both_columns():
    rows = cells(("P4:T1:R2", "84", "84A"), ("P4:T1:R3", "", "84B"))

    assert checks.missing_housing_types(sheet(("84A", 84.9, [])), rows, []) == [
        "원본 4쪽 공급표의 주택형 84(주택타입 84B)이(가) 추출 결과에 없어 "
        "저장하면 지금 공급표의 그 주택형이 지워질 수 있음"]


def test_total_rows_and_cells_listing_several_types_are_not_missing_types():
    rows = cells(("P5:T1:R2", "26A/26B", None), ("P5:T1:R3", "49A·49B", None), ("P5:T1:R4", "계", None),
                 ("P5:T1:R5", "", None), ("P5:T2:R2", "-", None))

    assert checks.missing_housing_types(sheet(("26A", 26.1, []), ("26B", 26.2, []), ("49A·49B", None, [])), rows, []) == []


def test_a_merged_cell_does_not_carry_over_to_another_table():
    rows = cells(("P6:T1:R2", "16A", None), ("P6:T2:R2", "", "36A"))

    assert checks.missing_housing_types(sheet(("16A", 16.37, [])), rows, []) == [
        "원본 6쪽 공급표의 주택형 36A이(가) 추출 결과에 없어 저장하면 지금 공급표의 그 주택형이 지워질 수 있음"]


def test_an_lh_type_with_no_matching_name_or_area_is_warned_once():
    extracted = sheet(("16A", None, []), ("36", 36.8, []))
    lh_rows = [lh("16A(대학생/청년)", "16.37"), lh("36A(고령자)", "36.80"), lh("44A(청년)", "44.1"),
               lh("44A(고령자)", "44.1"), lh("51", "")]

    assert checks.missing_housing_types(extracted, {}, lh_rows) == [
        "LH 공급정보의 주택형 44A(청년)(전용 44.10㎡)과 형명이나 전용면적이 같은 주택형이 추출 결과에 없음"]


def test_a_trailing_note_in_parentheses_does_not_make_a_type_missing():
    # 공급표가 31AS (주거약자용)으로 적고 결과가 31AS로 냈다. 화면도 끝 괄호를 떼고 주택형을 짝짓는다.
    assert checks.missing_housing_types(sheet(("31AS", 31.96, [])), cells(("P4:T1:R2", "31AS (주거약자용)", None)), []) == []
