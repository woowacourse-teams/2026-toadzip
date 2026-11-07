import json
from decimal import Decimal

import pytest

from ai_worker import checks
from ai_worker.extraction import PermanentError
from ai_worker.source_frame import SourceSupplyRow


def row(housing_type="26A", common_area=13.5, supply_area=None, move_in=None, target="일반", count=None, area=None,
        complex_name="여수미평1", subtypes=()):
    return {"complexReference": {"sourceComplexName": complex_name}, "sourceHousingTypeName": housing_type,
            "sourceRow": None, "expectedMoveInMonth": move_in, "exclusiveArea": area,
            "commonArea": common_area, "supplyArea": supply_area, "totalSupplyHouseholdCount": count,
            "subtypes": [{"name": name, "exclusiveArea": value} for name, value in subtypes],
            "targets": [{"target": target}]}


def lh(type_name="26", area="", count="", complex_label="여수미평1"):
    return SourceSupplyRow("LH", None, complex_label, type_name, area, "", count)


def response(rows=None, evidence=(), shared_counts=(), announcement=None, complexes=None):
    return {
        "data": {
            "announcement": announcement or {"name": "여수미평1 영구임대주택 예비입주자 모집", "postedDate": "2026-10-20",
                                             "applicationStartDate": "2026-11-10",
                                             "applicationEndDate": "2026-11-12", "winnerAnnouncementDate": None},
            "complexes": complexes or [{"sourceComplexName": "여수미평1"}],
            "supplyRows": rows if rows is not None else [row(target="가군(생계·의료급여수급자 등)")],
            "schedules": [],
        },
        "sharedCounts": list(shared_counts),
        "notApplicable": [],
        "evidence": list(evidence),
    }


def review(tree, pages=(1, 2, 10), texts=None, lh_rows=(), rental_rows=0):
    texts = texts if texts is not None else [""] * 10
    return checks.review(tree, list(pages), texts, list(lh_rows), rental_rows)


def test_remaps_evidence_and_shared_count_pages_to_original_pdf():
    tree = response(
        shared_counts=[{"paths": ["supplyRows[0].totalSupplyHouseholdCount"], "count": 20, "page": 3, "quote": "20"}],
        evidence=[{"path": "announcement.name", "page": 1, "quote": "모집"},
                  {"path": "schedules[0]", "page": 3, "quote": "‘26.11.10"}],
    )
    texts = ["예비입주자 모집", "공급 20호"] + [""] * 7 + ["신청기간 ‘26. 11. 10"]

    assert review(tree, texts=texts) == []
    assert tree["evidence"][1]["page"] == 10
    assert tree["sharedCounts"][0]["page"] == 10


def test_out_of_range_page_is_left_as_is_with_warning():
    tree = response(evidence=[{"path": "receptionPlace", "page": 7, "quote": "x"}])

    assert review(tree) == ["receptionPlace: 첨부한 쪽 범위를 벗어난 근거 쪽 번호(7)"]
    assert tree["evidence"][0]["page"] == 7


def test_warns_rules_the_model_often_breaks():
    tree = response(
        announcement={"name": "사천벌리1 영구임대주택 예비입주자 모집", "postedDate": "2026-10-16",
                      "applicationStartDate": "2026-10-28T09:00:00", "applicationEndDate": "2026-10-30",
                      "winnerAnnouncementDate": "2027-01-22"},
        complexes=[{"sourceComplexName": "사천벌리1 영구임대주택 예비입주자 모집"}],
        rows=[row("26.37", 13.5, 39.87, "2025-06", "「가」군")],
    )

    assert review(tree, pages=[1]) == [
        "announcement.applicationStartDate: 날짜 형식이 아님(2026-10-28T09:00:00)",
        "complexes[0].sourceComplexName: 단지명이 공고 제목 전체와 같음",
        "supplyRows[0].sourceHousingTypeName: 면적 숫자만 있음(26.37)",
        "supplyRows[0].supplyArea: 공용면적이 있는데 공급면적도 채워짐",
        "supplyRows[0].expectedMoveInMonth: 공고일보다 이른 연월(2025-06)",
        "supplyRows[0].targets[0].target: 표기 규칙 위반(「가」군)",
    ]


def test_warns_quote_words_missing_from_its_original_page():
    tree = response(evidence=[{"path": "supplyRows[0]", "page": 2, "quote": "26A 13.50 20"},
                              {"path": "announcement.name", "page": 1, "quote": "여수미평1 모집"}])
    texts = ["여수미평1  모집", "", "주택형 26A 공용 13.50 모집호수 19"] + [""] * 7

    # 첨부 2쪽은 원본 3쪽이다. 인용문은 원본 쪽에서 낱말마다 찾는다.
    assert review(tree, pages=[1, 3], texts=texts) == ["supplyRows[0]: 근거 인용문의 20이(가) 3쪽 원문에 없음"]


def test_quote_of_table_row_matches_cells_extracted_in_other_order():
    tree = response(evidence=[{"path": "supplyRows[0]", "page": 1, "quote": "경암부향 39 39.95 717"}])
    texts = ["39.95\n경암부향\n717 39"]

    assert review(tree, pages=[1], texts=texts) == []


@pytest.mark.parametrize(("value", "expected"), [
    # 26.375·84.9600은 백엔드 HousingTypeAreas 단위 테스트와 같은 경계값이다. 26.125·26.365는 round()·float 오차로 틀어지는 값이다.
    (26.375, Decimal("26.38")), ("26.375", Decimal("26.38")), ("84.9600", Decimal("84.96")), (84.96, Decimal("84.96")),
    (26.125, Decimal("26.13")), (26.365, Decimal("26.37")),
    (26.3749, Decimal("26.37")), (59, Decimal("59.00")), ("", None), ("면적", None), (None, None), (True, None),
])
def test_area_key_rounds_half_up_at_two_decimals(value, expected):
    assert checks.area_key(value) == expected


def test_several_lh_rows_of_one_area_attach_to_one_pdf_row_and_their_sum_is_compared():
    # 사천벌리1처럼 LH가 같은 주택형을 신청자 구분(우선·일반)으로 나눠 보내도 PDF 공급행은 하나다. 줄 수가 달라도 오류가 아니다.
    lh_rows = [lh("26(우선)", "26.37", "60"), lh("26(일반)", "26.3700", "50"), lh("31", "31.32", "30")]
    rows = [row("26", area=26.37, count=110), row("31", area=31.32, count=30)]

    assert review(response(rows=rows), lh_rows=lh_rows) == []
    rows[0]["totalSupplyHouseholdCount"] = 100
    assert review(response(rows=rows), lh_rows=lh_rows) == [
        "supplyRows[0].totalSupplyHouseholdCount: 같은 전용면적(26.37) LH API 공급행 2줄의 모집수 합계 110와 다름(100)",
    ]


def test_pdf_rows_of_one_area_with_different_type_names_are_compared_together_with_the_lh_rows_of_that_area():
    # 같은 면적에 형명만 다른 PDF 행(26A 10 / 26B 5)은 화면이 공급행 하나로 접는다. 행마다 LH 합계와 맞대면 거짓 경고가 난다.
    lh_rows = [lh("26A", "26.01", "10"), lh("26B", "26.01", "5")]
    rows = [row("26A", area=26.01, count=10), row("26B", area=26.01, count=5)]

    assert review(response(rows=rows), lh_rows=lh_rows) == []
    rows[1]["totalSupplyHouseholdCount"] = 4
    assert review(response(rows=rows), lh_rows=lh_rows) == [
        "supplyRows[0].totalSupplyHouseholdCount: 같은 전용면적(26.01) LH API 공급행 2줄의 모집수 합계 15와 "
        "같은 면적 PDF 행 2개(supplyRows[0], supplyRows[1])의 합 14가 다름",
    ]


def test_lh_area_is_compared_at_two_decimals_rounded_half_up():
    lh_rows = [lh("26", "26.375", "10"), lh("84", "84.9600", "5")]
    rows = [row("26", area=26.38, count=11), row("84", area=84.96, count=6)]

    assert review(response(rows=rows), lh_rows=lh_rows) == [
        "supplyRows[0].totalSupplyHouseholdCount: 같은 전용면적(26.38) LH API 공급행 1줄의 모집수 합계 10와 다름(11)",
        "supplyRows[1].totalSupplyHouseholdCount: 같은 전용면적(84.96) LH API 공급행 1줄의 모집수 합계 5와 다름(6)",
    ]


def test_subtype_areas_of_a_pdf_row_collect_lh_rows_of_each_area():
    # 공급 표가 두 주택형을 모집수 하나로 안내하면 한 공급행의 세부 주택형 면적마다 LH 행이 붙는다.
    shared = row("51A/B", count=240, subtypes=[("51A", 51.98), ("51B", 51.91)])
    lh_rows = [lh("51A", "51.98", "200"), lh("51B", "51.91", "40")]

    assert review(response(rows=[shared]), lh_rows=lh_rows) == []
    assert review(response(rows=[shared]), lh_rows=lh_rows[:1]) == [
        "supplyRows[0].totalSupplyHouseholdCount: 같은 전용면적(51.98, 51.91) LH API 공급행 1줄의 모집수 합계 200와 다름(240)",
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
        "supplyRows[1].totalSupplyHouseholdCount: 같은 전용면적(26.37) LH API 공급행 1줄의 모집수 합계 20와 다름(21)",
    ]


def test_one_lh_complex_and_one_pdf_complex_are_the_same_complex_even_if_named_differently():
    assert review(response(rows=[row("26", area=26.37, count=9, complex_name="사천벌리1")]),
                  lh_rows=[lh("26", "26.37", "10", "사천벌리 영구임대")]) == [
        "supplyRows[0].totalSupplyHouseholdCount: 같은 전용면적(26.37) LH API 공급행 1줄의 모집수 합계 10와 다름(9)",
    ]


def test_numeric_housing_type_copied_from_lh_api_is_not_warned():
    tree = response(rows=[row(housing_type="39"), row(housing_type="26.37")])

    assert review(tree, lh_rows=[lh("39"), lh("51")]) == [
        "supplyRows[1].sourceHousingTypeName: 면적 숫자만 있음(26.37)",
    ]


def test_integer_housing_type_from_pdf_is_not_area_even_if_lh_names_it_differently():
    # 공급 표가 "24", LH API가 "24A"인 공고. 정수는 흔한 주택형 이름이라 면적으로 경고하지 않는다.
    tree = response(rows=[row(housing_type="24")])

    assert review(tree, lh_rows=[lh("24A")]) == []


def test_lh_housing_type_name_missing_from_row_quote_is_warned():
    # 모델이 PDF 공급 표의 "24" 대신 틀로 받은 LH 이름 "24A"를 옮긴 경우. 근거 인용에 그 이름이 있으면 경고하지 않는다.
    tree = response(rows=[row(housing_type="24A"), row(housing_type="26")],
                    evidence=[{"path": "supplyRows[0]", "page": 1, "quote": "24 24.7200 12.6758 50"},
                              {"path": "supplyRows[1]", "page": 1, "quote": "26 26.8300 13.7 40"}])

    warnings = review(tree, texts=["24 24.7200 12.6758 50 26 26.8300 13.7 40"] + [""] * 9, lh_rows=[lh("24A"), lh("26")])

    assert warnings == ["supplyRows[0].sourceHousingTypeName: 근거 인용에 없는 LH 주택형 이름(24A)"]


def test_lh_housing_type_target_parentheses_need_not_be_in_quote():
    # 공급대상 괄호는 병합 셀이라 표 한 줄 인용에 없을 수 있다. 괄호 앞 표기(16A)가 있으면 경고하지 않는다.
    tree = response(rows=[row(housing_type="16A(대학생/청년)")],
                    evidence=[{"path": "supplyRows[0]", "page": 1, "quote": "16A 16.37 27.6384 78"}])

    assert review(tree, texts=["16A 16.37 27.6384 78"] + [""] * 9, lh_rows=[lh("16A(대학생/청년)")]) == []


def test_housing_type_not_from_lh_is_not_checked_against_quote():
    # LH 이름이 아니면(모델이 공급 표에서 읽은 표기) 인용에 없어도 이 경고는 내지 않는다. 화면이 근거와 대조한다.
    tree = response(rows=[row(housing_type="26B")], evidence=[{"path": "supplyRows[0]", "page": 1, "quote": "26 26.10 40"}])

    assert review(tree, texts=["26 26.10 40"] + [""] * 9, lh_rows=[lh("26A")]) == []


def test_without_lh_rows_counts_are_not_compared():
    assert review(response(rows=[row(count=20)])) == []


def test_fewer_targets_than_rental_rows_is_warned():
    tree = response(rows=[row(target="대학생"), row(target="청년")])

    assert review(tree, rental_rows=3) == ["supplyRows: 공급대상 2개가 임대조건표 3줄보다 적어 빠진 대상이 있을 수 있음"]
    assert review(response(rows=[row(target="대학생"), row(target="청년")]), rental_rows=2) == []


def test_malformed_items_do_not_stop_review():
    tree = response(rows=[row(), "row", None], evidence=[{"path": "a", "page": "2"}, "evidence", {"path": "b"}])
    tree["data"]["complexes"] = "complexes"

    assert review(tree) == ["a: 첨부한 쪽 범위를 벗어난 근거 쪽 번호(2)", "b: 첨부한 쪽 범위를 벗어난 근거 쪽 번호(None)"]


def test_reads_first_json_object_and_ignores_trailing_text():
    tree = response()

    assert checks.first_json_object("\n" + json.dumps(tree) + "\n{\"extra\": 1} 끝") == tree


@pytest.mark.parametrize("reply", [
    "```json", "{\"data\": ", "[1, 2]", "{\"result\": {}}", "{\"data\": []}", "{\"data\": {\"count\": NaN}}",
])
def test_reply_without_extraction_object_is_permanent_error(reply):
    with pytest.raises(PermanentError) as error:
        checks.first_json_object(reply)

    assert error.value.code == checks.INVALID_CODE


@pytest.mark.parametrize("field, value", [
    ("announcement", None), ("announcement", []), ("supplyRows", None), ("supplyRows", {}), ("schedules", None),
    ("schedules", "없음"),
])
def test_reply_with_wrong_data_shape_is_permanent_error(field, value):
    tree = response()
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
    ]}, "notApplicable": [{"path": "$.receptionPlace.address", "reason": "e"},
                          {"path": "receptionPlace.주소", "reason": "f"}, {"path": None, "reason": "g"}]}
    warnings = []

    checks.normalize_note_paths(tree, warnings)

    # 미확인은 범위를 넓혀도 보수적이라 앞부분까지 줄이고 경고한다.
    assert [item["path"] for item in tree["data"]["unresolvedFields"]] == [
        "complexes[*].parkingCount", "complexes[0]", "schedules[1]"]
    # 해당 없음을 상위로 넓히면 그 아래 값이 모두 반영에서 빠지므로 줄이지 않고 뺀다.
    assert [item["path"] for item in tree["notApplicable"]] == ["receptionPlace.address"]
    assert warnings == [
        "unresolvedFields: 읽을 수 없는 경로 data.complexes[0].주차대수를 complexes[0]로 넓혔습니다.",
        "unresolvedFields: 경로를 읽을 수 없는 항목을 뺐습니다(!!!: d)",
        "notApplicable: 경로를 읽을 수 없는 항목을 뺐습니다(receptionPlace.주소: f)",
        "notApplicable: 경로를 읽을 수 없는 항목을 뺐습니다(None: g)",
    ]
