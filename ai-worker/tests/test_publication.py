import time

from ai_worker import jev
from ai_worker import publication as policy
from ai_worker.jev import Meaning
from ai_worker.supply_sources import AMBIGUOUS_TABLE_NOTE

PAGES = ["행복주택 입주자 모집공고 접수 11월 3일", "주택형 36A 예비 모집 12", "유의사항과 계약 안내"]


def assessment(answers=None, quote="11월 3일", unresolved=(), source_rows=None, eligibility=None, warnings=(),
               failing=(), reread=None, judged=True, seconds=30, missing_date=None, unresolved_data=()):
    """answers는 질문 키별로 차례로 돌려줄 (선택, 확률) 목록이다. 없으면 첫 선택지를 0.99로 답한다."""
    notice = {field: None if field == missing_date else "2026-11-03" for field in policy.DATES}
    evidence = [{"path": "announcement." + field, "page": 1, "quote": quote} for field in notice]
    evidence.append({"path": "eligibility.targets[0].requirements[0]", "page": 3, "quote": "유의사항"})
    sheet = {"housingTypes": [1], "cells": [1], "quotas": [1], "unresolvedFields": list(unresolved),
             "evidence": [{"path": "housingTypes[0]", "page": 2, "quote": "36A"}]}
    tree = {"data": {"announcement": notice, "supplySheet": sheet, "schedules": [], "eligibility": eligibility,
                     "unresolvedFields": list(unresolved_data)},
            "evidence": evidence}
    scripted = {key: list(values) for key, values in (answers or {}).items()}
    calls = []

    class Judge:
        def choices(self, state, questions, timeout):
            calls.append((state, list(questions)))
            if failing and set(failing) & set(questions):
                raise jev.JevError("거절")
            return {key: Meaning(*(scripted[key].pop(0) if scripted.get(key) else (next(iter(q["criteria"])), 0.99)))
                    for key, q in questions.items()}

    decision = policy.assess(tree, PAGES, list(warnings), Judge() if judged else None, time.monotonic() + seconds,
                             "행복주택 모집공고",
                             reread or (lambda: [page + " (다시 읽음)" for page in PAGES]), source_rows)
    return decision, calls


def pages_sent_for(calls, key):
    return next(sorted(state["pages"]) for state, keys in calls if key in keys)


def test_verified_notice_approves_types_and_eligibility():
    decision, _ = assessment(eligibility={"targets": ["청년"]})
    assert decision["reasons"] == []
    assert (decision["rentalType"], decision["recruitmentType"]) == ("HAPPY_HOUSING", "NEW")
    assert decision["eligibilityVerified"] is True
    assert decision["evidence"]["winnerAnnouncementDate"]["page"] == 1


def test_each_judgment_sees_only_the_pages_it_needs():
    _, calls = assessment(source_rows={"P2:T1:R3": "주택형 36A 예비 모집 12"}, eligibility={"targets": ["청년"]})
    assert pages_sent_for(calls, "notice") == [1, 2]
    assert pages_sent_for(calls, "applicationEndDate") == [1]
    assert pages_sent_for(calls, "P2:T1:R3") == [2]
    assert pages_sent_for(calls, "eligibility") == [3]


def test_unclear_date_is_asked_again_from_the_reread_original_then_held():
    decision, calls = assessment({"applicationEndDate": [("unclear", 0.99), ("conflict", 0.99)]})
    state, _ = next(call for call in calls if call[1] == ["applicationEndDate"])
    assert state["pages"][1].endswith("(다시 읽음)")
    assert decision["reasons"] == ["신청 접수 종료일 원문 확인 필요 · 1쪽 11월 3일"]
    assert decision["rentalType"] is None


def test_answer_below_the_probability_bar_twice_holds_publication():
    decision, _ = assessment({"notice": [("confirmed", 0.9), ("confirmed", 0.9)]})
    assert decision["reasons"] == ["원공고인지 원문 확인 필요(정정·취소·안내문일 수 있음)"]


def test_unreadable_reread_holds_instead_of_approving_an_unclear_answer():
    decision, _ = assessment({"schedules": [("unclear", 0.99)]}, reread=lambda: PAGES[:1])
    assert decision["reasons"] == ["일정·접수처: 다시 읽을 원문을 모두 읽지 못했습니다."]


def test_missing_quote_or_unverified_supply_holds_without_model_calls():
    for kwargs in [{"quote": "없는 원문"}, {"unresolved": [{"path": "quotas[0]", "reason": "원문 불일치"}]},
                   {"warnings": ["원본 3쪽 공급표의 주택형 36B이(가) 추출 결과에 없음"]}]:
        decision, calls = assessment(**kwargs)
        assert decision["reasons"] and not calls


def test_repeated_unresolved_fields_become_one_readable_reason():
    fields = [{"path": f"housingTypes[{row}].complexName", "reason": "해당 열의 값을 확인하지 못함"} for row in range(3)]
    decision, _ = assessment(unresolved=fields)
    assert decision["reasons"] == ["주택형의 단지명 3건 미확인: 해당 열의 값을 확인하지 못함"]


def test_process_note_and_eligibility_warning_do_not_hold_the_notice():
    decision, _ = assessment(warnings=[AMBIGUOUS_TABLE_NOTE, "신청자격: 요건 1개를 뺐습니다."], eligibility={"targets": ["청년"]})
    assert decision["reasons"] == []
    assert decision["eligibilityVerified"] is False


def test_missing_recruitment_row_is_reported_with_its_original_page_and_text():
    decision, calls = assessment({"P2:T1:R3": [("conflict", 0.99)]}, source_rows={"P2:T1:R3": "주택형 36A 예비 모집 12"})
    assert decision["reasons"] == ["공급표 2쪽 행 원문 확인 필요 · 주택형 36A 예비 모집 12"]
    question = next(state for state, keys in calls if "P2:T1:R3" in keys)
    assert "evidence" not in question["supply"]


def test_rejected_jev_request_holds_that_judgment():
    decision, _ = assessment(failing=["supply"])
    assert decision["reasons"] == ["공급표 원문 대조 응답을 받지 못했습니다."]
    assert decision["rentalType"] is None


def test_conflicting_eligibility_keeps_the_notice_publishable_but_unverified():
    decision, _ = assessment({"eligibility": [("conflict", 0.99)]}, eligibility={"targets": ["청년"]})
    assert decision["reasons"] == []
    assert decision["rentalType"] == "HAPPY_HOUSING"
    assert decision["eligibilityVerified"] is False


def test_without_the_source_check_model_nothing_is_approved():
    decision, calls = assessment(judged=False)
    assert decision["reasons"] == ["원문 대조 모델(Jev)을 쓸 수 없습니다."] and not calls
    assert decision["rentalType"] is None


def test_no_time_left_for_the_source_check_holds_without_asking():
    decision, calls = assessment(seconds=0.5)
    assert not calls and decision["rentalType"] is None
    assert decision["reasons"][0] == "공고 성격 원문 대조 시간이 모자랍니다."
    assert all(reason.endswith("원문 대조 시간이 모자랍니다.") for reason in decision["reasons"])


def test_rental_type_the_model_cannot_name_is_held_and_not_approved():
    for answer in [("unclear", 0.99), ("NOT_A_RENTAL_TYPE", 0.99)]:
        decision, _ = assessment({"rentalType": [answer, answer]})
        assert decision["reasons"] == ["공급유형을 원문에서 하나로 정하지 못했습니다."]
        assert (decision["rentalType"], decision["recruitmentType"]) == (None, None)


def test_missing_date_holds_without_model_calls():
    decision, calls = assessment(missing_date="applicationEndDate")
    assert decision["reasons"] == ["신청 접수 종료일 값이 없습니다."] and not calls


def test_eligibility_with_an_unresolved_item_is_not_verified_and_not_asked():
    field = {"path": "eligibility.targets[0].requirements[0]", "reason": "원문에서 확인하지 못함"}
    decision, calls = assessment(eligibility={"targets": ["청년"]}, unresolved_data=[field])
    assert decision["reasons"] == [] and decision["eligibilityVerified"] is False
    assert not [keys for _, keys in calls if "eligibility" in keys]
