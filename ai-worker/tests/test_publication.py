import json
import time

import pytest

from ai_worker import eligibility as extraction
from ai_worker import jev
from ai_worker import publication as policy
from ai_worker.jev import Meaning
from ai_worker.supply_assembly import NO_SOURCE_VALUE, RESERVE_MISSING
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
               complex_names=("단지",), eligibility_quotes=None):
    """answers는 질문 키 → 답(Noul은 확률, Choice는 (선택, 확률))이다. 없는 키는 통과하는 답을 받는다.
    eligibility_quotes는 신청자격 근거 경로 → 인용문이고, 없으면 ELIGIBILITY의 두 요건 인용이다."""
    evidence = [{"path": "announcement." + field, "page": 1, "quote": quote} for field, quote in quotes.items()]
    if eligibility_quotes is None:
        eligibility_quotes = {"eligibility.common.requirements[0]": "만 19세 이상", "eligibility.common.requirements[1]": "유의사항"}
    evidence += [{"path": path, "page": 3, "quote": quote} for path, quote in eligibility_quotes.items()]
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
    assert decision["eligibilityPublishable"] is True and decision["unconfirmedRequirements"] == []
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
    """서버의 ETC(기타)는 수집이 분류하지 못한 공고다. 그대로 승인하지 않고 원문에 물어 얻은 유형을 낸다."""
    decision, calls = assessment(collected=COLLECTED | {"rentalType": "ETC", "recruitmentType": "ETC"})
    assert decision["reasons"] == []
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
    ({"rentalType": ("HAPPY_HOUSING", 0.89)}, "공급유형을 원문에서 하나로 정하지 못했습니다."),
    ({"rentalType": ("NOT_A_RENTAL_TYPE", 0.99)}, "공급유형을 원문에서 하나로 정하지 못했습니다."),
    ({"recruitmentType": ("NEW", 0.89)}, "모집유형(신규·예비)을 원문에서 하나로 정하지 못했습니다."),
])
def test_undecided_notice_kind_holds_publication(answers, reason):
    decision, _ = assessment(answers, collected={"title": "행복주택 모집공고"})
    assert decision["reasons"] == [reason]
    assert (decision["rentalType"], decision["recruitmentType"]) == (None, None)


def test_type_and_date_step_pass_at_their_own_minimum_probabilities():
    """유형은 고른 답이 그대로 공개 값이라 0.9, 날짜의 절차는 기대한 절차와 같아야만 통과하므로 0.7부터 받는다."""
    at_minimum = {"rentalType": ("HAPPY_HOUSING", 0.9), "recruitmentType": ("NEW", 0.9),
                  "applicationEndDate": ("application", 0.7), "winnerAnnouncementDate": ("final_announcement", 0.7)}
    decision, _ = assessment(at_minimum, collected={"title": "공고"})
    assert decision["reasons"] == [] and decision["rentalType"] == "HAPPY_HOUSING"
    below, _ = assessment({"winnerAnnouncementDate": ("final_announcement", 0.69)}, collected={"title": "공고"})
    assert len(below["reasons"]) == 1 and "어느 절차의 날짜인지 원문 확인 필요" in below["reasons"][0]


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


@pytest.mark.parametrize("page, quote", [
    ("신청접수 11.03-11.05 발표 12/01", "신청접수 11.03-11.05"),      # 앞 날짜의 일(03)을 연도로 읽지 않는다
    ("신청접수 11/03-11/05 발표 12/01", "신청접수 11/03-11/05"),
    ("신청접수 2026.11.03~11.05 발표 12/01", "2026.11.03~11.05"),
])
def test_end_date_written_after_a_range_separator_is_found_in_its_citation(page, quote):
    assert policy.date_citations(
        {"data": {"announcement": {"applicationEndDate": "2026-11-05"}},
         "evidence": [{"path": "announcement.applicationEndDate", "page": 1, "quote": quote}]},
        [page])[0]["applicationEndDate"]["written"].endswith(("11.05", "11/05"))


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


def test_blank_complex_names_pass_only_when_the_table_has_no_complex_column_at_all():
    field = {"path": "housingTypes[0].complexName", "reason": NO_SOURCE_VALUE}
    single, _ = assessment(unresolved=[field], complex_names=("", ""))
    assert single["reasons"] == []
    mixed, _ = assessment(unresolved=[field], complex_names=("", "가람단지"))
    assert mixed["reasons"] == [f"주택형의 단지명 1건 미확인: {NO_SOURCE_VALUE}"]
    # 추출이 단지명을 냈는데 원문에서 확인되지 않아 비운 표는 단지 구분이 있는 표다.
    unverified = "추출한 값이 같은 의미의 원문 표 열에서 확인되지 않음"
    named, _ = assessment(unresolved=[field | {"reason": unverified}], complex_names=("", ""))
    assert named["reasons"] == [f"주택형의 단지명 1건 미확인: {unverified}"]


RESERVE = {"path": "cells[0].reserve", "reason": RESERVE_MISSING}


def test_new_recruitment_is_approved_without_a_reserve_count_the_table_does_not_give():
    collected, _ = assessment(unresolved=[RESERVE])
    assert collected["reasons"] == [] and collected["recruitmentType"] == "NEW"
    asked_type, _ = assessment(unresolved=[RESERVE], collected={"title": "공고"})
    assert asked_type["reasons"] == [] and asked_type["recruitmentType"] == "NEW"
    # 수치가 원문과 맞지 않는 예비자 수는 신규 모집이어도 보류한다.
    mismatch, _ = assessment(unresolved=[RESERVE | {"reason": "추출한 수치가 같은 의미의 원문 표 열에서 확인되지 않음"}])
    assert mismatch["reasons"] == ["예비자 수 1건 미확인: 추출한 수치가 같은 의미의 원문 표 열에서 확인되지 않음"]


def test_reserve_only_recruitment_without_a_reserve_count_is_held():
    """예비자만 뽑는 공고에서 예비자 수가 없으면 모집 수가 없는 것이다."""
    reason = f"예비자 수 1건 미확인: {RESERVE_MISSING}"
    collected, calls = assessment(unresolved=[RESERVE], collected=COLLECTED | {"recruitmentType": "WAITLIST"})
    assert collected["reasons"] == [reason] and not calls
    asked_type, _ = assessment({"recruitmentType": ("WAITLIST", 0.99)}, unresolved=[RESERVE], collected={"title": "공고"})
    assert asked_type["reasons"] == [reason]


def test_unverified_reception_place_holds_the_notice_but_unverified_eligibility_does_not():
    place, calls = assessment(unresolved_data=[{"path": "receptionPlaces[0].url", "reason": "원문 불일치"}])
    assert place["reasons"] == ["receptionPlaces[].url 1건 미확인: 원문 불일치"] and not calls
    field = {"path": "eligibility.common.requirements[0]", "reason": "원문 불일치"}
    eligibility, calls = assessment(unresolved_data=[field], eligibility=ELIGIBILITY)
    assert eligibility["reasons"] == [] and eligibility["eligibilityPublishable"] is True
    assert "eligibility.common.requirements[0]" in asked(calls)


def test_eligibility_warnings_hold_neither_the_notice_nor_its_eligibility():
    """요건 하나의 경고는 추출 단계가 그 요건의 숫자를 비워 서버가 판정하지 않는 목록으로 둔 것이다. 신청자격 공개를 막지 않는다."""
    warnings = [AMBIGUOUS_TABLE_NOTE, "신청자격: 어느 신청 경로에도 쓰이지 않은 요건 1개를 뺐습니다(소득).",
                "신청자격 요건 '만 19세 이상'(나이): 숫자 확인 필요. 원문과 대조했지만 확정하지 못했습니다."]
    decision, calls = assessment(warnings=warnings, eligibility=ELIGIBILITY)
    assert decision["reasons"] == [] and decision["eligibilityPublishable"] is True
    assert "eligibility.common.requirements[0]" in asked(calls)


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


def test_requirement_the_quote_does_not_confirm_is_left_out_of_judging_and_eligibility_still_publishes():
    decision, calls = assessment({"eligibility.common.requirements[0]": 0.5}, eligibility=ELIGIBILITY)
    assert decision["reasons"] == [] and decision["rentalType"] == "HAPPY_HOUSING"
    assert decision["eligibilityPublishable"] is True
    assert decision["unconfirmedRequirements"] == ["eligibility.common.requirements[0]"]
    _, questions = calls[-1]
    # 요건의 뜻은 사람이 읽는 문장으로 묻는다. 판정하지 않는 요건(기타)은 묻지 않는다.
    instructions = questions["eligibility.common.requirements[0]"]["instructions"]
    assert instructions["quote"] == "만 19세 이상"
    assert instructions["condition"].startswith("The applicant is aged 19 or older")
    assert list(questions) == ["eligibility.common.requirements[0]"]


def test_requirement_sentences_leave_out_values_the_quote_does_not_carry():
    """LH 표본에서 맞는 요건이 떨어진 원인: 질문에 보인 기준 연도, 원 단위 금액, 내부 코드가 인용문에 없었다."""
    income = {"kind": "INCOME", "asWritten": "월평균소득이 전년도 도시근로자 가구원수별 가구당 월평균소득의 100퍼센트 이하",
              "scope": "HOUSEHOLD", "incomeStandard": "URBAN_WORKER_MONTHLY_INCOME", "incomeBaseYear": 2025, "percent": 100,
              "householdSizeExtraPercent": [{"householdSize": 2, "percent": 10}],
              "adjustments": [{"kind": "DUAL_INCOME", "asWritten": "맞벌이 부부 120퍼센트 이하"}]}
    assets = {"kind": "ASSETS", "asWritten": "총 자산가액 합산기준 34,500만원 이하", "scope": "HOUSEHOLD",
              "amountAsWritten": "34,500만원", "maximumKrw": 345000000, "adjustments": [{"kind": "DISABILITY", "asWritten": "장애인"}]}
    family = {"kind": "FAMILY_TYPE", "asWritten": "(신혼부부) 혼인중인 자", "familyTypes": ["COUPLE_OR_ENGAGED"]}
    child = {"kind": "AGE", "asWritten": "6세 이하 자녀(태아포함)를 둔 자", "scope": "YOUNGEST_CHILD", "maximum": 6}
    sentences = [policy.meaning(requirement) for requirement in (income, assets, family, child)]
    for leaked in ("2025", "345000000", "COUPLE_OR_ENGAGED", "URBAN_WORKER", "HOUSEHOLD"):
        assert not [sentence for sentence in sentences if leaked in sentence]
    assert "100%" in sentences[0] and "dual-income" in sentences[0] and "household sizes" in sentences[0]
    # 서버가 기본 기준으로 판정하는 가산(장애인)은 말하지 않는다. 인용문에 그 가산이 있으면 확인되지 않고 목록으로 간다.
    assert sentences[1] == "The total assets of the applicant's household are at most 34,500만원."
    assert "married, or planning to marry" in sentences[2]
    # 서버 입력(막내 생년월일 또는 출산예정일)은 태아도 센다. 빠뜨리면 '태아 포함' 인용문에 맞는 요건도 확인되지 않는다.
    assert "a child aged 6 or younger" in sentences[3] and "unborn" in sentences[3]


# 서버(ExtractedEligibilityDraft.rule)가 판정하는 데 쓰는 최소 값. 새 종류가 생기면 여기서 실패해 문장을 만들게 한다.
# 문장이 없으면 그 종류는 묻지도 않고 늘 목록으로 내려가, 원문 대조로 확인하지 못한 요건만 내린다는 기준보다 넓어진다.
JUDGED = {"AGE": {"scope": "APPLICANT", "minimum": 19}, "NO_HOUSE": {"scope": "HOUSEHOLD"}, "NO_CAR": {"scope": "APPLICANT"},
          "INCOME": {"scope": "HOUSEHOLD", "incomeStandard": "STANDARD_MEDIAN_INCOME", "percent": 50},
          "ASSETS": {"scope": "HOUSEHOLD", "amountAsWritten": "3억원", "maximumKrw": 300000000},
          "CAR_VALUE": {"scope": "APPLICANT", "amountAsWritten": "4,542만원", "maximumKrw": 45420000},
          "RESIDENCE": {"regions": ["서울특별시"], "minimumMonths": 12}, "MARRIAGE_PERIOD": {"maximum": 7},
          "FAMILY_TYPE": {"familyTypes": ["SINGLE_PARENT"]}, "UNIVERSITY_STUDENT": {}, "JOB_SEEKER": {}}


@pytest.mark.parametrize("kind", sorted(extraction.KINDS - {"OTHER", "ANY_OF"}))
def test_every_kind_the_server_judges_gets_a_sentence(kind):
    assert policy.meaning({"kind": kind, "asWritten": "원문"} | JUDGED[kind])


def test_every_family_type_and_income_standard_the_extraction_emits_has_words():
    assert set(extraction.FAMILY_TYPES) == policy.FAMILIES.keys()
    assert extraction.INCOME_STANDARDS == policy.INCOME_STANDARDS.keys()


def test_contract_example_unconfirmed_path_is_one_the_worker_emits():
    """서버(ExtractionContractDraftTest)가 같은 예시로 이 경로의 요건을 목록으로 내리는지 확인한다. 두 쪽의 경로 형식이 같아야 한다."""
    tree = json.loads(contract_example("result.example.json")["json"])
    unconfirmed = tree["publicationReview"]["unconfirmedRequirements"]
    assert unconfirmed and set(unconfirmed) <= {path for path, _ in policy.requirements(tree["data"]["eligibility"])}


def test_eligibility_with_only_unjudged_requirements_publishes_without_asking():
    """기타 요건만 있으면 판정할 것이 없다. 추출이 없는 경우와 달리 신청자격을 공개하고 기타는 서버가 목록으로 둔다."""
    only_other = {"common": {"requirements": [{"kind": "OTHER", "asWritten": "유의사항"}]}}
    decision, calls = assessment(eligibility=only_other)
    assert decision["eligibilityPublishable"] is True and decision["unconfirmedRequirements"] == []
    assert not [key for key in asked(calls) if key.startswith("eligibility")]


def test_any_of_requirement_is_confirmed_only_when_every_option_is():
    any_of = {"kind": "ANY_OF", "asWritten": "아래 중 하나에 해당하는 사람", "options": [
        {"kind": "MARRIAGE_PERIOD", "asWritten": "혼인기간 7년 이내", "maximum": 7},
        {"kind": "AGE", "asWritten": "6세 이하 자녀를 둔 자", "scope": "YOUNGEST_CHILD", "maximum": 6}]}
    eligibility = {"common": {"requirements": [REQUIREMENT]}, "targets": [{"target": "신혼부부", "requirements": [any_of]}]}
    path = "eligibility.targets[0].requirements[0]"
    quotes = {"eligibility.common.requirements[0]": "만 19세 이상", path: "아래 중 하나에 해당하는 사람",
              f"{path}.options[0]": "혼인기간 7년 이내", f"{path}.options[1]": "6세 이하 자녀를 둔 자"}

    passed, calls = assessment(eligibility=eligibility, eligibility_quotes=quotes)
    # '아래 중 하나' 안내 문장은 묻지 않고 선택지를 하나씩 묻는다.
    assert [key for key in asked(calls) if key.startswith(path)] == [f"{path}.options[0]", f"{path}.options[1]"]
    assert passed["unconfirmedRequirements"] == []
    held, _ = assessment({f"{path}.options[1]": 0.6}, eligibility=eligibility, eligibility_quotes=quotes)
    assert held["eligibilityPublishable"] is True and held["unconfirmedRequirements"] == [path]


def test_judged_requirement_without_a_quote_or_a_value_is_unconfirmed_without_asking():
    """근거가 없거나 비운 숫자의 요건은 서버도 판정하지 않는다. 묻지 않고 확인하지 못한 요건으로 둔다."""
    empty_age = {"kind": "AGE", "asWritten": "성년자", "scope": "APPLICANT", "minimum": None, "maximum": None}
    no_house = {"kind": "NO_HOUSE", "asWritten": "무주택세대구성원", "scope": "HOUSEHOLD"}
    eligibility = {"common": {"requirements": [empty_age, no_house, REQUIREMENT]}}
    quotes = {"eligibility.common.requirements[0]": "성년자", "eligibility.common.requirements[2]": "만 19세 이상"}
    decision, calls = assessment(eligibility=eligibility, eligibility_quotes=quotes)
    assert [key for key in asked(calls) if key.startswith("eligibility")] == ["eligibility.common.requirements[2]"]
    assert decision["unconfirmedRequirements"] == ["eligibility.common.requirements[0]", "eligibility.common.requirements[1]"]
    assert decision["eligibilityPublishable"] is True


def test_eligibility_without_an_extraction_is_not_published():
    """신청자격 단계가 실패해 추출이 없으면 공개할 신청자격이 없다. 공고는 그대로 공개한다."""
    decision, _ = assessment(eligibility=None)
    assert decision["reasons"] == [] and decision["rentalType"] == "HAPPY_HOUSING"
    assert decision["eligibilityPublishable"] is False


def test_requirements_without_a_source_check_answer_are_listed_and_eligibility_is_published():
    """원문 대조 응답을 받지 못한 요건은 확인하지 못한 요건이다. 신청자격을 검수로 남기지 않고 그 요건을 목록으로 내린 채 공개한다."""
    decision, _ = assessment(eligibility=ELIGIBILITY, failing=["eligibility.common.requirements[0]"])
    assert decision["reasons"] == [] and decision["rentalType"] == "HAPPY_HOUSING"
    assert decision["eligibilityPublishable"] is True
    assert decision["unconfirmedRequirements"] == ["eligibility.common.requirements[0]"]


@pytest.mark.parametrize("written, sentence", [
    ("세대구성원 전원이 보유하고 있는 개별 자동차가액 4,542만원 이하",
     "Each car owned by the applicant's household is worth at most 4,542만원 (개별 자동차가액)."),
    ("총 자산 중 자동차가액이 4,542만원 이하일 것",
     "The car value (자동차가액) of the applicant's household is at most 4,542만원.")])
def test_car_value_sentence_follows_how_the_notice_counts_cars(written, sentence):
    """서버는 차량 한 대씩 판정한다. 공고문이 '개별'이라 쓸 때만 한 대씩으로 묻고, 아니면 공고문처럼 세대의 자동차가액으로 묻는다.
    출산자녀 가산은 말하지 않는다. 자동차가액 인용문에는 가산이 따로 적혀 말하면 맞는 요건도 확인되지 않았다."""
    car = {"kind": "CAR_VALUE", "asWritten": written, "scope": "HOUSEHOLD", "amountAsWritten": "4,542만원",
           "maximumKrw": 45420000, "adjustments": [{"kind": "CHILDBIRTH", "asWritten": "출산자녀 1인 10%p 가산"}]}
    assert policy.meaning(car) == sentence


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
