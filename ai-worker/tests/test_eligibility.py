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
    """모델이 인용에 없는 숫자를 내면(계산·추정) 판정에 쓰지 않는다. 한 숫자라도 실패하면 그 요건의 숫자를 모두 비운다.
    나이 상한만 비우고 하한을 남기면 서버가 상한을 제한 없음으로 읽어 판정이 넓어진다."""
    reply = _reply(common=_group(
        {"kind": "AGE", "scope": "APPLICANT", "minimum": 19, "maximum": 39, "asWritten": "만 19세 이상 39세 이하"},
        {"kind": "INCOME", "scope": "HOUSEHOLD", "incomeStandard": "URBAN_WORKER_MONTHLY_INCOME",
         "incomeYearAsWritten": "전년도", "percent": 70, "householdSizeExtraPercent": [{"householdSize": 1, "percent": 20}],
         "asWritten": "월평균소득 70% 이하"},
        {"kind": "ASSETS", "scope": "HOUSEHOLD", "amountAsWritten": "3억 3,700만원", "asWritten": "총자산 3억 3,700만원 이하"},
        {"kind": "CAR_VALUE", "scope": "HOUSEHOLD", "amountAsWritten": "3,708만원", "asWritten": "자동차 3,708만원 이하"},
    ), evidence=[
        {"path": "eligibility.common.requirements[0]", "page": 3, "quote": "만 19세 이상 만 40세 이하인 자"},
        {"path": "eligibility.common.requirements[1]", "page": 3, "quote": "70% 이하(1인 가구 90%)"},
        {"path": "eligibility.common.requirements[2]", "page": 4, "quote": "총자산가액 3억 3,700만원 이하"},
        {"path": "eligibility.common.requirements[3]", "page": 4, "quote": "자동차가액 3,557만원 이하"},
    ])

    result = eligibility.normalize(reply, "2026-09-30")

    age, income, assets, car = result.data["common"]["requirements"]
    assert (age["minimum"], age["maximum"]) == (None, None)
    assert (income["percent"], income["incomeBaseYear"], income["householdSizeExtraPercent"]) == (
        70, 2025, [{"householdSize": 1, "percent": 20}])
    assert (assets["maximumKrw"], car["maximumKrw"]) == (337_000_000, None)
    assert result.warnings == [
        "eligibility.common.requirements[0].maximum: 근거 인용에 없는 숫자(39)라 비웠습니다.",
        "eligibility.common.requirements[3].amountAsWritten: 근거 인용에 없는 금액(3,708만원)이라 비웠습니다.",
    ]


def test_secondary_numbers_are_checked_against_the_evidence_page_and_amounts_on_a_number_boundary():
    """가구원 가산 최종 비율과 거주 개월은 짧은 인용 대신 근거 쪽 글에서 찾는다. 금액은 10,800만원 안의 800만원처럼 숫자 중간에서 맞추지 않는다."""
    reply = _reply(common=_group(
        {"kind": "INCOME", "scope": "HOUSEHOLD", "incomeStandard": "URBAN_WORKER_MONTHLY_INCOME", "incomeYearAsWritten": "전년도",
         "percent": 100, "householdSizeExtraPercent": [{"householdSize": 1, "percent": 20}], "asWritten": "100% 이하"},
        {"kind": "RESIDENCE", "regions": ["인천광역시"], "minimumMonths": 24, "asWritten": "인천 2년 이상 거주"},
        {"kind": "ASSETS", "scope": "APPLICANT", "amountAsWritten": "800만원", "asWritten": "자산"},
        {"kind": "INCOME", "scope": "HOUSEHOLD", "incomeStandard": "URBAN_WORKER_MONTHLY_INCOME", "incomeYearAsWritten": "전년도",
         "percent": 100, "householdSizeExtraPercent": [{"householdSize": 1, "percent": 50}], "asWritten": "100% 이하"},
    ), evidence=[
        {"path": "eligibility.common.requirements[0]", "page": 3, "quote": "월평균소득의 100퍼센트 이하"},
        {"path": "eligibility.common.requirements[1]", "page": 4, "quote": "인천광역시에 거주"},
        {"path": "eligibility.common.requirements[2]", "page": 3, "quote": "총자산가액 10,800만원 이하"},
        {"path": "eligibility.common.requirements[3]", "page": 3, "quote": "월평균소득의 100퍼센트 이하"},
    ])
    pages = {3: "단, 가구원수가 1인인 경우에는 120퍼센트, 2인인 경우 110퍼센트", 4: "입주자모집공고일 현재 2년 이상 계속 거주"}

    income, residence, assets, wrong_extra = eligibility.normalize(reply, "2026-09-30", pages).data["common"]["requirements"]

    assert (income["percent"], income["householdSizeExtraPercent"]) == (100, [{"householdSize": 1, "percent": 20}])
    assert residence["minimumMonths"] == 24
    assert assets["maximumKrw"] is None
    # 가구원 가산 최종 비율(150%)이 근거 쪽에 없으면 그 소득 요건의 숫자를 모두 비운다.
    assert (wrong_extra["percent"], wrong_extra["householdSizeExtraPercent"]) == (None, [])


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


def test_sub_route_summary_reaches_its_supply_target_condition_through_merge():
    """하위 경로(대학생 계층의 취업준비생)의 요약은 그 경로가 속한 공급표 대상(대학생)의 신청 조건에 붙는다."""
    from ai_worker import merge

    extracted = eligibility.normalize(_reply(targets=[
        {"target": "취업준비생", "supplyTarget": "대학생",
         **_group({"kind": "JOB_SEEKER", "asWritten": "졸업 2년 이내"}, summary="졸업·중퇴 2년 이내 취업준비생")}],
        evidence=[{"path": "eligibility.targets[0].requirements[0]", "page": 3, "quote": "졸업 또는 중퇴한 지 2년 이내"}]),
        "2026-09-30")
    supply = {"data": {"complexes": [], "unresolvedFields": [], "supplyRows": [
        {"complexReference": {"sourceComplexName": "인천석남"}, "targets": [{"target": "대학생", "applicationCondition": None}]}]},
        "sharedCounts": [], "notApplicable": [], "evidence": []}
    notice = {"data": {"announcement": {}, "receptionPlaces": [], "complexes": [], "schedules": [], "unresolvedFields": []},
              "notApplicable": [], "evidence": []}

    tree, _ = merge.merge(supply, notice, extracted)

    assert tree["data"]["supplyRows"][0]["targets"][0]["applicationCondition"] == "졸업·중퇴 2년 이내 취업준비생"
    assert tree["data"]["eligibility"]["targets"][0]["supplyTargets"] == ["대학생"]


def test_evidence_written_under_data_or_with_a_data_prefix_is_still_read():
    """모델이 근거를 data.evidence에 두거나 경로 앞에 data.를 붙여도 같은 근거로 읽는다."""
    reply = _reply(common=_group({"kind": "ASSETS", "scope": "HOUSEHOLD", "amountAsWritten": "24,500만원", "asWritten": "총자산"},
                                 {"kind": "AGE", "scope": "APPLICANT", "minimum": 65, "asWritten": "65세 이상"}))
    reply["data"]["evidence"] = [{"path": "data.eligibility.common.requirements[0]", "page": 4, "quote": "총자산 24,500만원 이하"}]
    reply["evidence"] = [{"path": "$.eligibility.common.requirements[1]", "page": 4, "quote": "만 65세 이상"}]

    result = eligibility.normalize(reply, "2026-10-06")

    assets, age = result.data["common"]["requirements"]
    assert (assets["maximumKrw"], age["minimum"]) == (245_000_000, 65)
    assert [item["path"] for item in result.evidence] == [
        "eligibility.common.requirements[1]", "eligibility.common.requirements[0]"]


def test_dropped_qualification_and_exclusion_do_not_shift_the_following_evidence():
    """이름 없는 자격·제목 없는 제한을 빼도 뒤 항목에는 제 근거가 붙고, 뺀 항목의 근거는 버린다."""
    reply = _reply(common=_group(qualifications=[
        {"exactName": " ", "legalBasis": None, "conditionsAsWritten": "빈 자격"},
        {"exactName": "지원대상 한부모가족", "legalBasis": None, "conditionsAsWritten": "한부모"}]),
        exclusions=[{"title": "", "body": "제목 없음"}, {"title": "재당첨 제한", "body": "공공임대 당첨자"}],
        evidence=[{"path": "eligibility.common.qualifications[0]", "page": 1, "quote": "빈 자격"},
                  {"path": "eligibility.common.qualifications[1]", "page": 7, "quote": "지원대상 한부모가족"},
                  {"path": "eligibility.exclusions[0]", "page": 2, "quote": "제목 없음"},
                  {"path": "eligibility.exclusions[1]", "page": 9, "quote": "재당첨 제한"}])

    result = eligibility.normalize(reply, "2026-10-06")

    assert [(item["path"], item["page"]) for item in result.evidence] == [
        ("eligibility.common.qualifications[0]", 7), ("eligibility.exclusions[0]", 9)]


def test_closed_lists_match_the_schema_given_to_the_model():
    """스키마와 코드의 닫힌 목록이 다르면 한쪽 값이 조용히 OTHER(직접 확인)로 바뀌므로 같아야 한다."""
    import json
    from importlib.resources import files

    schema = json.loads((files("ai_worker") / "prompts" / "announcement-pdf-eligibility-schema.json").read_text(encoding="utf-8"))
    requirement = schema["$defs"]["requirement"]["properties"]

    assert set(requirement["kind"]["enum"]) == eligibility.KINDS
    assert set(requirement["scope"]["enum"]) - {None} == eligibility.SCOPES
    assert set(requirement["incomeStandard"]["enum"]) - {None} == eligibility.INCOME_STANDARDS
    assert tuple(requirement["familyTypes"]["items"]["enum"]) == eligibility.FAMILY_TYPES
    assert set(requirement["adjustments"]["items"]["properties"]["kind"]["enum"]) == eligibility.ADJUSTMENTS


def test_amount_written_in_a_form_blank_matches_the_quote():
    reply = _reply(common=_group({"kind": "ASSETS", "scope": "HOUSEHOLD", "amountAsWritten": "24,500만원", "asWritten": "총자산"}),
                   evidence=[{"path": "eligibility.common.requirements[0]", "page": 8, "quote": "총 자산 가액 합산 기준 ( 24,500 )만원 이하"}])

    [assets] = eligibility.normalize(reply, "2026-10-06").data["common"]["requirements"]

    assert assets["maximumKrw"] == 245_000_000


@pytest.mark.parametrize("months, page, regions", [
    (18, "인천광역시에 1년 6개월 이상 계속 거주", ["인천광역시"]),
    (18, "1순위 인천광역시 거주자 2년 이상", []),
    ("24", "인천광역시 2년 이상 거주", []),
])
def test_unverified_residence_months_clear_the_regions_too(months, page, regions):
    """거주기간을 대조하지 못하면 지역도 비운다. 개월만 비우면 서버가 '지역 거주'만 판정해 넓어진다.
    쪽 글은 단위가 붙은 형태(1년 6개월)만 인정하고, '1순위' 같은 숫자로 통과시키지 않는다."""
    reply = _reply(common=_group({"kind": "RESIDENCE", "regions": ["인천광역시"], "minimumMonths": months,
                                  "asWritten": "인천 거주"}),
                   evidence=[{"path": "eligibility.common.requirements[0]", "page": 4, "quote": "인천광역시에 거주"}])

    [residence] = eligibility.normalize(reply, "2026-09-30", {4: page}).data["common"]["requirements"]

    assert residence["regions"] == regions
    assert residence["minimumMonths"] == (18 if regions else None)


def test_evidence_under_data_gets_original_page_numbers():
    """모델이 근거를 data 안에 두어도 첨부 쪽 번호를 원본 쪽 번호로 바꾼다(첨부 2쪽 = 원본 3쪽)."""
    import json
    from ai_worker import announcement
    from ai_worker.openai import Reply

    content = json.dumps({"data": {"eligibility": {
        "common": {"requirements": [{"kind": "AGE", "scope": "APPLICANT", "minimum": 65, "asWritten": "65세 이상"}],
                   "qualifications": [], "summary": None}, "targets": [], "exclusions": []},
        "evidence": [{"path": "eligibility.common.requirements[0]", "page": 2, "quote": "만 65세 이상"}]}},
        ensure_ascii=False)
    notice = {"data": {"announcement": {"postedDate": "2026-09-30"}}}

    extracted = announcement._eligibility(Reply(content, {}), None, notice, [1, 3], ["표지", "다른 쪽", "만 65세 이상"])

    assert [(item["path"], item["page"]) for item in extracted.evidence] == [("eligibility.common.requirements[0]", 3)]
    assert extracted.data["common"]["requirements"][0]["minimum"] == 65
