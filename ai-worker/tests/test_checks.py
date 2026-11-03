import json

import pytest

from ai_worker import checks
from ai_worker.extraction import PermanentError


def row(housing_type="26A", common_area=13.5, supply_area=None, move_in=None, target="일반", count=None):
    return {"sourceHousingTypeName": housing_type, "expectedMoveInMonth": move_in, "commonArea": common_area,
            "supplyArea": supply_area, "totalSupplyHouseholdCount": count, "targets": [{"target": target}]}


def response(rows=None, evidence=(), shared_counts=(), announcement=None, complexes=None):
    return {
        "data": {
            "announcement": announcement or {"name": "여수미평1 영구임대주택 예비입주자 모집", "postedDate": "2026-10-20",
                                             "applicationStartDate": "2026-11-10",
                                             "applicationEndDate": "2026-11-12", "winnerAnnouncementDate": None},
            "complexes": complexes or [{"sourceComplexName": "여수미평1"}],
            "supplyRows": rows if rows is not None else [row(target="가군(생계·의료급여수급자 등)")],
        },
        "sharedCounts": list(shared_counts),
        "notApplicable": [],
        "evidence": list(evidence),
    }


def review(tree, pages=(1, 2, 10), texts=None, lh_types=(), lh_counts=(), rental_rows=0):
    texts = texts if texts is not None else [""] * 10
    return checks.review(tree, list(pages), texts, list(lh_types), list(lh_counts), rental_rows)


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


def test_lh_row_counts_must_match_supply_rows():
    tree = response(rows=[row(count=20), row(count=None), row(count=7), row(count=45)])

    # 비어 있는 행 모집수는 공통 모집수로 묶인 행이고, LH API 0은 모집수를 모른다는 뜻이라 맞대지 않는다.
    assert review(tree, lh_counts=[20, 15, 8, 0]) == [
        "supplyRows[2].totalSupplyHouseholdCount: LH API 모집수 8와 다름(7)",
    ]


def test_numeric_housing_type_copied_from_lh_api_is_not_warned():
    tree = response(rows=[row(housing_type="39"), row(housing_type="26.37")])

    assert review(tree, lh_types=["39", "51"], lh_counts=[None, None]) == [
        "supplyRows[1].sourceHousingTypeName: 면적 숫자만 있음(26.37)",
    ]


def test_integer_housing_type_from_pdf_is_not_area_even_if_lh_names_it_differently():
    # 공급 표가 "24", LH API가 "24A"인 공고. 정수는 흔한 주택형 이름이라 면적으로 경고하지 않는다.
    tree = response(rows=[row(housing_type="24")])

    assert review(tree, lh_types=["24A"], lh_counts=[None]) == []


def test_lh_housing_type_name_missing_from_row_quote_is_warned():
    # 모델이 PDF 공급 표의 "24" 대신 틀로 받은 LH 이름 "24A"를 옮긴 경우. 근거 인용에 그 이름이 있으면 경고하지 않는다.
    tree = response(rows=[row(housing_type="24A"), row(housing_type="26")],
                    evidence=[{"path": "supplyRows[0]", "page": 1, "quote": "24 24.7200 12.6758 50"},
                              {"path": "supplyRows[1]", "page": 1, "quote": "26 26.8300 13.7 40"}])

    warnings = review(tree, texts=["24 24.7200 12.6758 50 26 26.8300 13.7 40"] + [""] * 9, lh_types=["24A", "26"], lh_counts=[None, None])

    assert warnings == ["supplyRows[0].sourceHousingTypeName: 근거 인용에 없는 LH 주택형 이름(24A)"]


def test_lh_housing_type_target_parentheses_need_not_be_in_quote():
    # 공급대상 괄호는 병합 셀이라 표 한 줄 인용에 없을 수 있다. 괄호 앞 표기(16A)가 있으면 경고하지 않는다.
    tree = response(rows=[row(housing_type="16A(대학생/청년)")],
                    evidence=[{"path": "supplyRows[0]", "page": 1, "quote": "16A 16.37 27.6384 78"}])

    assert review(tree, texts=["16A 16.37 27.6384 78"] + [""] * 9, lh_types=["16A(대학생/청년)"], lh_counts=[None]) == []


def test_housing_type_not_from_lh_is_not_checked_against_quote():
    # LH 이름이 아니면(모델이 공급 표에서 읽은 표기) 인용에 없어도 이 경고는 내지 않는다. 화면이 근거와 대조한다.
    tree = response(rows=[row(housing_type="26B")], evidence=[{"path": "supplyRows[0]", "page": 1, "quote": "26 26.10 40"}])

    assert review(tree, texts=["26 26.10 40"] + [""] * 9, lh_types=["26A"], lh_counts=[None]) == []


def test_lh_row_count_mismatch_is_warned_once():
    tree = response(rows=[row(count=20)])

    assert review(tree, lh_counts=[20, 15]) == ["supplyRows: LH API 공급행 2개와 개수가 다름(1개)"]


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
