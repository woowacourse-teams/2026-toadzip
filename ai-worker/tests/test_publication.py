import json
import time

import pytest

from ai_worker import jev
from ai_worker import publication as policy
from ai_worker.jev import Meaning
from ai_worker.supply_sources import AMBIGUOUS_TABLE_NOTE
from conftest import contract_example

PAGES = ["행복주택 입주자 모집공고 신청접수 ‘26.11.03.(화) 10:00 ~ 11.05.(목) 발표 ’26.12.01.(화)",
         "주택형 36A 12 주택형 36B 5", "유의사항과 계약 안내"]
SCHEDULE = [["신청접수", "당첨자 발표"], ["‘26.11.03.(화) 10:00 ~ 11.05.(목)", "’26.12.01.(화) 14:00 이후"]]
ROWS = {"P2:T1:R1": "주택형\t모집", "P2:T1:R2": "36A\t12", "P2:T1:R3": "\t(-) 3,000", "P3:T1:R1": "소득\t100%"}
COLLECTED = {"title": "행복주택 모집공고", "rentalType": "HAPPY_HOUSING", "recruitmentType": "NEW",
             "applicationStartDate": "2026-11-03", "applicationEndDate": "2026-11-05",
             "winnerAnnouncementDate": "2026-12-01"}
NOTICE = {"applicationStartDate": "2026-11-03", "applicationEndDate": "2026-11-05", "winnerAnnouncementDate": "2026-12-01"}
QUOTES = {"applicationStartDate": "신청접수 '26.11.03.(화) 10:00", "applicationEndDate": "~ 11.05.(목)",
          "winnerAnnouncementDate": "'26.12.01.(화) 14:00 이후"}
# 질문 종류별로 통과하는 답
PASSING = {"recruits": 0.99, "cancelled": 0.01, "rentalListed": 0.99, "mixedRental": 0.01,
           "rentalType": ("HAPPY_HOUSING", 0.99), "recruitmentType": ("NEW", 0.99),
           "applicationStartDate": ("application", 0.99), "applicationEndDate": ("application", 0.99),
           "winnerAnnouncementDate": ("final_announcement", 0.99)}
REQUIREMENT = {"kind": "AGE", "asWritten": "만 19세 이상", "scope": "APPLICANT", "minimum": 19}
ELIGIBILITY = {"common": {"requirements": [REQUIREMENT, {"kind": "OTHER", "asWritten": "유의사항"}]}}


def assessment(answers=None, collected=COLLECTED, notice=NOTICE, quotes=QUOTES, unresolved=(), rows=ROWS,
               eligibility=None, warnings=(), failing=(), judged=True, seconds=30, unresolved_data=(), tables=(SCHEDULE,),
               complex_names=("단지",)):
    """answers는 질문 키 → 답(Noul은 확률, Choice는 (선택, 확률))이다. 없는 키는 통과하는 답을 받는다."""
    evidence = [{"path": "announcement." + field, "page": 1, "quote": quote} for field, quote in quotes.items()]
    evidence += [{"path": "eligibility.common.requirements[0]", "page": 3, "quote": "만 19세 이상"},
                 {"path": "eligibility.common.requirements[1]", "page": 3, "quote": "유의사항"}]
    sheet = {"housingTypes": [{"name": "36A", "complexName": name} for name in complex_names], "cells": [1], "quotas": [1],
             "unresolvedFields": list(unresolved), "evidence": [{"path": "housingTypes[0]", "page": 2, "quote": "36A\t12"}]}
    tree = {"data": {"announcement": dict(notice), "supplySheet": sheet, "schedules": [], "eligibility": eligibility,
                     "unresolvedFields": list(unresolved_data)},
            "evidence": evidence}
    calls = []

    class Judge:
        def ask(self, state, questions, timeout):
            calls.append((state, questions))
            if set(failing) & set(questions):
                raise jev.JevError("거절")
            result = {}
            for key, question in questions.items():
                default = PASSING.get(key, 0.99 if key.startswith("eligibility") else 0.01)
                answer = (answers or {}).get(key, default)
                result[key] = answer if question["type"] == "noul" else Meaning(*answer)
            return result

    source = policy.Source(PAGES, rows, lambda page: list(tables) if page == 1 else [])
    decision = policy.assess(tree, source, list(warnings), Judge() if judged else None, time.monotonic() + seconds, collected)
    return decision, calls


def asked(calls):
    return [key for _, questions in calls for key in questions]


def test_notice_agreeing_with_the_collected_values_is_approved_without_asking_about_them():
    decision, calls = assessment(eligibility=ELIGIBILITY)
    assert decision["reasons"] == []
    assert (decision["rentalType"], decision["recruitmentType"]) == ("HAPPY_HOUSING", "NEW")
    assert decision["eligibilityVerified"] is True
    assert decision["evidence"]["winnerAnnouncementDate"]["page"] == 1
    # 수집값과 같은 유형·날짜는 묻지 않는다. 인용되지 않은 공급표 행과 구조화된 요건만 묻는다.
    assert asked(calls) == ["recruits", "cancelled", "P2:T1:R3", "eligibility.common.requirements[0]"]


def test_values_the_collection_did_not_have_are_asked_and_approved_from_the_answers():
    decision, calls = assessment(collected={"title": "행복주택 모집공고"})
    assert decision["reasons"] == []
    assert (decision["rentalType"], decision["recruitmentType"]) == ("HAPPY_HOUSING", "NEW")
    assert set(asked(calls)) >= {"rentalType", "rentalListed", "mixedRental", "recruitmentType", *policy.DATES}
    state, questions = next(call for call in calls if "winnerAnnouncementDate" in call[1])
    assert state == {"schedule": SCHEDULE}
    assert "26.12.01" in questions["winnerAnnouncementDate"]["instructions"]


def test_collected_type_outside_the_choices_is_asked_like_a_missing_one():
    decision, calls = assessment(collected=COLLECTED | {"rentalType": "ETC", "recruitmentType": "ETC"})
    assert (decision["rentalType"], decision["recruitmentType"]) == ("HAPPY_HOUSING", "NEW")
    assert {"rentalType", "rentalListed", "mixedRental", "recruitmentType"} <= set(asked(calls))
    assert not set(policy.DATES) & set(asked(calls))


def test_collected_values_in_the_contract_input_example_are_the_ones_read_here():
    """서버가 쓰는 입력 예시(Spring PdfExtractionContractTest가 같은 파일로 확인)의 수집값 이름과 값 모양을 그대로 읽는다."""
    collected = contract_example("input.example.json")["publication"]
    assert {"rentalType", "recruitmentType", *policy.DATES} <= collected.keys()
    assert collected["rentalType"] in policy.RENT and collected["recruitmentType"] in policy.RECRUIT
    decision, calls = assessment(collected=collected, notice=NOTICE | {
        field: collected[field] for field in ("applicationStartDate", "applicationEndDate")})
    # 예시에서 비어 있는 발표일만 묻는다.
    assert decision["reasons"] == [] and set(policy.DATES) & set(asked(calls)) == {"winnerAnnouncementDate"}


def test_approved_decision_has_the_shape_of_the_contract_result_example():
    """서버(PublicationCorrection)가 읽는 결론의 이름과, 운영자가 근거로 보는 evidence·answers의 모양."""
    example = json.loads(contract_example("result.example.json")["json"])["publicationReview"]
    decision, _ = assessment(collected=COLLECTED | {"winnerAnnouncementDate": None})
    assert decision.keys() == example.keys() == policy.held().keys()
    assert {key: type(value) for key, value in decision.items()} == {key: type(value) for key, value in example.items()}
    assert decision["evidence"].keys() == example["evidence"].keys() == policy.DATES.keys()
    assert all(cite.keys() == {"path", "page", "quote", "written"}
               for review in (decision, example) for cite in review["evidence"].values())
    shapes = {key: answer.keys() for key, answer in decision["answers"].items()}
    assert shapes == {"recruits": {"noul"}, "cancelled": {"noul"}, "P2:T1:R3": {"noul"},
                      "winnerAnnouncementDate": {"choice", "probability"}}
    assert {key: answer.keys() for key, answer in example["answers"].items()} == {
        "P4:T1:R5" if key == "P2:T1:R3" else key: shape for key, shape in shapes.items()}


@pytest.mark.parametrize("answers, reason", [
    ({"recruits": 0.5}, "입주자 모집공고문인지 확인 필요(안내문·서식·결과 발표일 수 있음)"),
    ({"cancelled": 0.5}, "모집 취소·철회 공고인지 확인 필요"),
    ({"mixedRental": 0.6}, "여러 공급유형을 함께 모집하는 공고인지 확인 필요"),
    ({"rentalListed": 0.6}, "공급유형이 분류 목록에 있는지 확인 필요"),
    ({"rentalType": ("HAPPY_HOUSING", 0.9)}, "공급유형을 원문에서 하나로 정하지 못했습니다."),
    ({"rentalType": ("NOT_A_RENTAL_TYPE", 0.99)}, "공급유형을 원문에서 하나로 정하지 못했습니다."),
    ({"recruitmentType": ("WAITLIST", 0.7)}, "모집유형(신규·예비)을 원문에서 하나로 정하지 못했습니다."),
])
def test_undecided_notice_kind_holds_publication(answers, reason):
    decision, _ = assessment(answers, collected={"title": "행복주택 모집공고"})
    assert decision["reasons"] == [reason]
    assert (decision["rentalType"], decision["recruitmentType"]) == (None, None)


def test_date_of_another_step_is_held_even_when_the_answer_is_confident():
    decision, _ = assessment({"winnerAnnouncementDate": ("shortlist_announcement", 0.99)}, collected={"title": "공고"})
    assert decision["reasons"] == [
        "최종 당첨자 또는 예비입주자 발표일 2026-12-01이 어느 절차의 날짜인지 원문 확인 필요 · 1쪽 '26.12.01.(화) 14:00 이후"]


def test_date_outside_any_schedule_table_is_held_without_asking_about_it():
    decision, calls = assessment(collected={"title": "공고"}, tables=())
    assert len(decision["reasons"]) == 3 and "applicationStartDate" not in asked(calls)
    assert decision["reasons"][0] == ("신청 접수 시작일 2026-11-03을 일정표에서 찾지 못해 어느 절차의 날짜인지 확인할 수 없습니다"
                                      " · 1쪽 신청접수 '26.11.03.(화) 10:00")


def test_pdf_date_differing_from_the_collected_date_is_held_without_model_calls():
    decision, calls = assessment(collected=COLLECTED | {"applicationEndDate": "2026-11-06"})
    assert decision["reasons"] == ["신청 접수 종료일: 수집한 값 2026-11-06와 PDF의 2026-11-05가 다릅니다."] and not calls


@pytest.mark.parametrize("quotes", [
    QUOTES | {"applicationEndDate": "~ 11.06.(금)"},                      # 인용에 그 날짜가 없음
    QUOTES | {"winnerAnnouncementDate": "'27.12.01.(화)"},                 # 연도가 다름
    QUOTES | {"applicationStartDate": "신청접수 2026년 11월 3일"},           # 인용의 날짜 표현이 쪽 원문에 없음
])
def test_date_whose_citation_does_not_show_it_on_the_page_is_held_without_model_calls(quotes):
    decision, calls = assessment(quotes=quotes)
    assert len(decision["reasons"]) == 1 and decision["reasons"][0].endswith("의 원문 쪽·인용을 확인할 수 없습니다.")
    assert not calls


def test_missing_date_holds_without_model_calls():
    decision, calls = assessment(notice=NOTICE | {"applicationEndDate": None})
    assert decision["reasons"] == ["신청 접수 종료일 값이 없습니다."] and not calls


def test_unverified_supply_holds_without_model_calls():
    for kwargs in [{"unresolved": [{"path": "quotas[0]", "reason": "원문 불일치"}]},
                   {"warnings": ["원본 3쪽 공급표의 주택형 36B이(가) 추출 결과에 없음"]}]:
        decision, calls = assessment(**kwargs)
        assert decision["reasons"] and not calls


def test_repeated_unresolved_fields_become_one_readable_reason():
    fields = [{"path": f"housingTypes[{row}].exclusiveArea", "reason": "해당 열의 값을 확인하지 못함"} for row in range(3)]
    decision, _ = assessment(unresolved=fields)
    assert decision["reasons"] == ["전용면적 3건 미확인: 해당 열의 값을 확인하지 못함"]


def test_blank_complex_names_hold_only_when_some_housing_type_names_a_complex():
    field = {"path": "housingTypes[0].complexName", "reason": "해당 열의 값을 확인하지 못함"}
    single, _ = assessment(unresolved=[field], complex_names=("", ""))
    assert single["reasons"] == []
    mixed, _ = assessment(unresolved=[field], complex_names=("", "가람단지"))
    assert mixed["reasons"] == ["주택형의 단지명 1건 미확인: 해당 열의 값을 확인하지 못함"]


def test_process_note_and_eligibility_warning_do_not_hold_the_notice():
    decision, _ = assessment(warnings=[AMBIGUOUS_TABLE_NOTE, "신청자격: 요건 1개를 뺐습니다."], eligibility=ELIGIBILITY)
    assert decision["reasons"] == []
    assert decision["eligibilityVerified"] is False


def test_uncited_row_that_may_recruit_is_reported_with_its_page_and_text():
    rows = ROWS | {"P2:T1:R4": "36B\t5"}
    decision, calls = assessment({"P2:T1:R4": 0.75}, rows=rows)
    assert decision["reasons"] == ["공급표 2쪽 행이 추출 결과에 빠졌을 수 있음 · 36B 5"]
    _, questions = next(call for call in calls if "P2:T1:R4" in call[1])
    # 머리글 행, 인용된 행, 인용된 행이 없는 표(소득표)의 행은 묻지 않는다.
    assert list(questions) == ["P2:T1:R3", "P2:T1:R4"]
    assert questions["P2:T1:R4"]["instructions"]["table_headers"] == ["주택형\t모집"]


def test_many_possibly_missing_rows_are_summarised():
    rows = ROWS | {f"P2:T1:R{number}": f"36B\t{number}" for number in range(4, 12)}
    decision, _ = assessment({row: 0.9 for row in rows}, rows=rows)
    assert len(decision["reasons"]) == 6 and decision["reasons"][-1] == "공급표 행 4건 더 확인 필요"


def test_supply_evidence_not_found_in_the_table_rows_is_held():
    decision, _ = assessment(rows={"P2:T1:R1": "다른 표"})
    assert decision["reasons"] == ["공급표 근거를 원문 표 행에서 찾지 못해 빠진 행이 없는지 확인할 수 없습니다."]


def test_rejected_jev_request_holds_that_judgment():
    decision, _ = assessment(failing=["P2:T1:R3"])
    assert decision["reasons"] == ["공급표 원문 대조 응답을 받지 못했습니다."]
    assert decision["rentalType"] is None


def test_eligibility_requirement_its_quote_does_not_state_keeps_the_notice_publishable_but_unverified():
    decision, calls = assessment({"eligibility.common.requirements[0]": 0.5}, eligibility=ELIGIBILITY)
    assert decision["reasons"] == [] and decision["rentalType"] == "HAPPY_HOUSING"
    assert decision["eligibilityVerified"] is False
    _, questions = calls[-1]
    # 원문을 옮긴 글은 빼고 구조화한 값만 묻는다. 글만 있는 요건은 묻지 않는다.
    assert questions["eligibility.common.requirements[0]"]["instructions"]["requirement"] == {
        "kind": "AGE", "scope": "APPLICANT", "minimum": 19}
    assert list(questions) == ["eligibility.common.requirements[0]"]


def test_eligibility_with_an_unresolved_item_is_not_verified_and_not_asked():
    field = {"path": "eligibility.common.requirements[0]", "reason": "원문에서 확인하지 못함"}
    decision, calls = assessment(eligibility=ELIGIBILITY, unresolved_data=[field])
    assert decision["reasons"] == [] and decision["eligibilityVerified"] is False
    assert not [key for key in asked(calls) if key.startswith("eligibility")]


def test_without_the_source_check_model_nothing_is_approved():
    decision, calls = assessment(judged=False)
    assert decision["reasons"] == ["원문 대조 모델(Jev)을 쓸 수 없습니다."] and not calls
    assert decision["rentalType"] is None


def test_no_time_left_for_the_source_check_holds_without_asking():
    decision, calls = assessment(seconds=0.5)
    assert not calls and decision["rentalType"] is None
    assert decision["reasons"] == ["공고 성격 원문 대조 시간이 모자랍니다.", "공급표 원문 대조 시간이 모자랍니다."]


def test_questions_beyond_one_request_are_split():
    rows = ROWS | {f"P2:T1:R{number}": f"\t(-) {number}" for number in range(4, 60)}
    decision, calls = assessment(rows=rows)
    assert decision["reasons"] == []
    assert [len(questions) for _, questions in calls if "P2:T1:R3" in questions or "P2:T1:R50" in questions] == [40, 17]
