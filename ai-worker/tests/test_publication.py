import time

from ai_worker import publication as policy
from ai_worker.jev import Meaning


def assessment(choices=(), quote="11월 3일", unresolved=(), source_rows=None, eligibility=None):
    pages = ["공고 접수 11월 3일", "주택형 36A 예비 모집 12"]
    notice = {field: "2026-11-03" for field in policy.DATES}
    evidence = [{"path": "announcement." + field, "page": 1, "quote": quote} for field in notice]
    tree = {"data": {"announcement": notice, "supplySheet": {"housingTypes": [1], "cells": [1], "quotas": [1],
                                                             "unresolvedFields": unresolved}}, "evidence": evidence}
    tree["data"]["eligibility"] = eligibility
    calls = []

    class Judge:
        def choices(self, state, questions, timeout):
            calls.append(list(questions))
            return {key: Meaning(dict(choices[len(calls)-1]).get(key, next(iter(q["criteria"]))), 0.99)
                    for key, q in questions.items()}

    return policy.assess(tree, pages, [], Judge(), time.monotonic()+30, "공고", lambda: pages, source_rows), calls


def test_indeterminate_date_is_reread_and_conflicting_date_holds_publication():
    decision, calls = assessment([[("applicationEndDate", "unclear")], [("applicationEndDate", "conflict")]])
    assert calls[1] == ["applicationEndDate"]
    assert decision["reasons"] == ["신청 접수 종료일 원문 확인 필요 · 1쪽 11월 3일"]


def test_complete_verified_notice_carries_types_and_date_evidence():
    decision, _ = assessment([[]])
    assert decision["reasons"] == []
    assert decision["answers"]["rentalType"]["choice"] == "HAPPY_HOUSING"
    assert decision["evidence"]["winnerAnnouncementDate"]["page"] == 1


def test_missing_quote_or_unverified_supply_holds_without_model_calls():
    for kwargs in [{"quote": "없는 원문"}, {"unresolved": [{"path": "quotas[0]", "reason": "원문 불일치"}]}]:
        decision, calls = assessment(**kwargs)
        assert decision["reasons"] and not calls


def test_missing_recruitment_row_is_reported_with_its_original_page_and_text():
    decision, _ = assessment([[("P2:T1:R3", "conflict")]], source_rows={"P2:T1:R3": "주택형 36A 예비 모집 12"})
    assert decision["reasons"] == ["P2:T1:R3 원문 확인 필요 · 2쪽 주택형 36A 예비 모집 12"]


def test_unclear_eligibility_does_not_hold_the_notice():
    decision, _ = assessment([[("eligibility", "conflict")]], eligibility={"targets": ["청년"]})
    assert not decision["reasons"]
    assert decision["answers"]["eligibility"]["choice"] == "conflict"
