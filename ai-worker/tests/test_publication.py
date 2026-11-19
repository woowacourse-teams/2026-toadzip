import time

from ai_worker import publication as policy
from ai_worker.jev import Meaning


def assessment(choices=(), quote="11월 3일", unresolved=()):
    pages = ["공고 접수 11월 3일"]
    notice = {field: "2026-11-03" for field in policy.DATES}
    evidence = [{"path": "announcement." + field, "page": 1, "quote": quote} for field in notice]
    tree = {"data": {"announcement": notice, "supplySheet": {"housingTypes": [1], "cells": [1], "quotas": [1],
                                                             "unresolvedFields": unresolved}}, "evidence": evidence}
    calls = []

    class Judge:
        def choices(self, state, questions, timeout):
            calls.append(list(questions))
            return {key: Meaning(dict(choices[len(calls)-1]).get(key, next(iter(q["criteria"]))), 0.99)
                    for key, q in questions.items()}

    return policy.assess(tree, pages, [], Judge(), time.monotonic()+30, "공고", lambda: pages), calls


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
