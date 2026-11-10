import pytest

from ai_worker import eligibility


def _reply(common=None, targets=None, exclusions=None, evidence=None):
    return {"data": {"eligibility": {
        "common": common or {"requirements": [], "qualifications": [], "summary": None},
        "targets": targets or [], "exclusions": exclusions or []}}, "evidence": evidence or []}


def _group(*requirements, qualifications=(), summary=None):
    return {"requirements": list(requirements), "qualifications": list(qualifications), "summary": summary}


@pytest.mark.parametrize("written, expected", [
    ("10,800만원", 108_000_000), ("3억 3,700만원", 337_000_000), ("37,080천원", 37_080_000),
    ("1억원", 100_000_000), ("45,420,000원", 45_420_000), ("24,500만원 이하", 245_000_000), ("약 3억", None), ("만원", None),
])
def test_amount_written_in_korean_units_becomes_won(written, expected):
    assert eligibility.amount_krw(written) == expected


def test_numbers_missing_from_the_evidence_quote_are_cleared_with_a_warning():
    """모델이 인용에 없는 숫자를 내면(계산·추정) 판정에 쓰지 않도록 비운다. 인용에 있는 숫자는 남긴다."""
    reply = _reply(common=_group(
        {"kind": "AGE", "scope": "APPLICANT", "minimum": 19, "maximum": 39, "asWritten": "만 19세 이상 39세 이하"},
        {"kind": "INCOME", "scope": "HOUSEHOLD", "incomeStandard": "URBAN_WORKER_MONTHLY_INCOME",
         "incomeYearAsWritten": "전년도", "percent": 70, "householdSizeExtraPercent": [{"householdSize": 1, "percent": 20}],
         "asWritten": "월평균소득 70% 이하"},
        {"kind": "ASSETS", "scope": "HOUSEHOLD", "amountAsWritten": "3억 3,700만원", "asWritten": "총자산 3억 3,700만원 이하"},
        {"kind": "CAR_VALUE", "scope": "HOUSEHOLD", "amountAsWritten": "3,708만원", "asWritten": "자동차 3,708만원 이하"},
    ), evidence=[
        {"path": "eligibility.common.requirements[0]", "page": 3, "quote": "만 19세 이상 만 40세 이하인 자"},
        {"path": "eligibility.common.requirements[1]", "page": 3, "quote": "월평균소득의 70% 이하"},
        {"path": "eligibility.common.requirements[2]", "page": 4, "quote": "총자산가액 3억 3,700만원 이하"},
        {"path": "eligibility.common.requirements[3]", "page": 4, "quote": "자동차가액 3,557만원 이하"},
    ])

    result = eligibility.normalize(reply, "2026-09-30")

    age, income, assets, car = result.data["common"]["requirements"]
    assert (age["minimum"], age["maximum"]) == (19, None)
    assert (income["percent"], income["incomeBaseYear"], income["householdSizeExtraPercent"]) == (
        70, 2025, [{"householdSize": 1, "percent": 20}])
    assert (assets["maximumKrw"], car["maximumKrw"]) == (337_000_000, None)
    assert result.warnings == [
        "eligibility.common.requirements[0].maximum: 근거 인용에 없는 숫자(39)라 비웠습니다.",
        "eligibility.common.requirements[3].amountAsWritten: 근거 인용에 없는 금액(3,708만원)이라 비웠습니다.",
    ]


@pytest.mark.parametrize("written, expected", [("전년도", 2025), ("2026년", 2026), ("당해연도", 2026), ("최근", None)])
def test_income_base_year_is_resolved_from_the_posted_date(written, expected):
    reply = _reply(common=_group({"kind": "INCOME", "incomeYearAsWritten": written, "asWritten": "소득"}))

    [income] = eligibility.normalize(reply, "2026-09-30").data["common"]["requirements"]

    assert income["incomeBaseYear"] == expected


def test_unknown_kinds_and_nested_any_of_become_other_and_scopes_are_closed():
    reply = _reply(common=_group(
        {"kind": "SAVINGS_ACCOUNT", "asWritten": "청약통장 가입"},
        {"kind": "ANY_OF", "asWritten": "대학생 또는 취업준비생", "options": [
            {"kind": "UNIVERSITY_STUDENT", "asWritten": "대학 재학"},
            {"kind": "ANY_OF", "asWritten": "중첩", "options": []}]},
        {"kind": "NO_HOUSE", "scope": "FAMILY", "asWritten": "무주택"},
        {"kind": "FAMILY_TYPE", "familyTypes": ["COUPLE_OR_ENGAGED", "DIVORCED", "UNMARRIED"], "asWritten": "혼인 중이 아닐 것"},
    ))

    other, any_of, no_house, family = eligibility.normalize(reply, None).data["common"]["requirements"]

    assert other == {"kind": "OTHER", "asWritten": "청약통장 가입"}
    assert [option["kind"] for option in any_of["options"]] == ["UNIVERSITY_STUDENT", "OTHER"]
    assert no_house["scope"] is None
    assert family["familyTypes"] == ["UNMARRIED", "COUPLE_OR_ENGAGED"]


def test_dropped_target_moves_the_following_target_evidence_and_summaries_become_conditions():
    """이름 없는 대상을 빼면 뒤 대상의 근거 경로를 새 순번으로 옮기고, 요약은 그 묶음 첫 근거와 함께 신청 조건이 된다."""
    reply = _reply(
        common=_group({"kind": "NO_HOUSE", "scope": "HOUSEHOLD", "asWritten": "무주택세대구성원"},
                      summary="무주택세대구성원"),
        targets=[
            {"target": " ", **_group({"kind": "OTHER", "asWritten": "빈 대상"}, summary="버림")},
            {"target": "고령자", **_group({"kind": "AGE", "scope": "APPLICANT", "minimum": 65, "asWritten": "65세 이상"},
                                       qualifications=[{"exactName": "주거급여 수급자", "legalBasis": "「주거급여법」",
                                                        "conditionsAsWritten": "수급자"}], summary="65세 이상")},
        ],
        exclusions=[{"title": "재당첨 제한", "body": "공공임대 당첨자"}, {"title": "", "body": "제목 없음"}],
        evidence=[
            {"path": "eligibility.common.requirements[0]", "page": 2, "quote": "무주택세대구성원"},
            {"path": "eligibility.targets[0].requirements[0]", "page": 2, "quote": "빈 대상"},
            {"path": "eligibility.targets[1].requirements[0]", "page": 5, "quote": "만 65세 이상"},
            {"path": "eligibility.targets[1].qualifications[0]", "page": 5, "quote": "주거급여 수급자"},
            {"path": "eligibility.unknown", "page": 1, "quote": "버림"},
        ])

    result = eligibility.normalize(reply, "2026-09-30")

    assert [(target["target"], target["supplyTarget"]) for target in result.data["targets"]] == [("고령자", "고령자")]
    assert result.data["targets"][0]["requirements"][0]["minimum"] == 65
    assert result.data["exclusions"] == [{"title": "재당첨 제한", "body": "공공임대 당첨자"}]
    assert [(item["path"], item["page"]) for item in result.evidence] == [
        ("eligibility.common.requirements[0]", 2), ("eligibility.targets[0].requirements[0]", 5),
        ("eligibility.targets[0].qualifications[0]", 5)]
    assert result.conditions == [{"target": None, "condition": "무주택세대구성원"},
                                 {"target": "고령자", "condition": "65세 이상"}]
    assert [(item["path"], item["page"]) for item in result.condition_evidence] == [
        ("applicationConditions[0]", 2), ("applicationConditions[1]", 5)]
    assert result.warnings == ["eligibility.targets[0]: 대상 이름이 없어 뺐습니다."]


def test_adult_age_option_numbers_and_amount_suffix_are_checked_against_their_own_quotes():
    """민법상 성년자는 숫자 없이도 19세다. 선택 요건은 제 근거 인용이 있으면 그것으로, 금액의 '이하'는 떼고 대조한다."""
    reply = _reply(common=_group(
        {"kind": "AGE", "scope": "APPLICANT", "minimum": 19, "asWritten": "민법상 성년자"},
        {"kind": "ANY_OF", "asWritten": "혼인 7년 이내 또는 6세 이하 자녀", "options": [
            {"kind": "MARRIAGE_PERIOD", "maximum": 7, "asWritten": "혼인기간 7년 이내"},
            {"kind": "AGE", "scope": "YOUNGEST_CHILD", "maximum": 6, "asWritten": "6세 이하 자녀"}]},
        {"kind": "ASSETS", "scope": "HOUSEHOLD", "amountAsWritten": "24,500만원 이하", "asWritten": "총자산"},
    ), evidence=[
        {"path": "eligibility.common.requirements[0]", "page": 1, "quote": "민법상 성년자인 무주택세대구성원"},
        {"path": "eligibility.common.requirements[1]", "page": 2, "quote": "6세 이하 자녀(태아포함)를 둔 자"},
        {"path": "eligibility.common.requirements[1].options[0]", "page": 2, "quote": "혼인기간이 7년 이내일 것"},
        {"path": "eligibility.common.requirements[2]", "page": 3, "quote": "총자산가액 24,500만원 이하"},
    ])

    result = eligibility.normalize(reply, "2026-10-06")

    adult, any_of, assets = result.data["common"]["requirements"]
    assert adult["minimum"] == 19
    assert [option.get("maximum") for option in any_of["options"]] == [7, 6]
    assert assets["maximumKrw"] == 245_000_000
    assert result.warnings == []
    assert "eligibility.common.requirements[1].options[0]" in [item["path"] for item in result.evidence]


def test_missing_eligibility_reply_yields_empty_groups():
    result = eligibility.normalize({"data": {}}, "2026-09-30")

    assert result.data == {"common": {"requirements": [], "qualifications": [], "summary": None},
                           "targets": [], "exclusions": []}
    assert (result.evidence, result.conditions) == ([], [])
