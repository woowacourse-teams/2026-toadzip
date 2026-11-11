import re
from importlib.resources import files

import pytest

from ai_worker import eligibility

# 첨부 순번 N은 원본 N + 10쪽이다. 쪽 번호를 그대로 쓰면 첨부 순번을 원본 쪽으로 바꾸지 않아도 테스트가 통과한다.
_FIRST_SOURCE_PAGE = 11


def _normalize(requirements, routes=(), pages=None, posted="2026-09-30", **rest):
    """requirements에 id(r1, r2 …)와 첨부 1쪽을 기본으로 붙여 정리한다. pages는 첨부 순서대로의 쪽 글이고,
    주지 않으면 요건·자격·제한 문구가 모두 적힌 한 쪽이다."""
    requirements = [{"id": f"r{index}", "page": 1, **item} for index, item in enumerate(requirements, 1)]
    if pages is None:
        written = [*requirements, *rest.get("qualifications", []), *rest.get("exclusions", []), rest.get("rankLimit") or {}]
        pages = ["\n".join(str(item.get(key, "")) for item in written for key in ("text", "name", "title", "body"))]
    numbers = list(range(_FIRST_SOURCE_PAGE, _FIRST_SOURCE_PAGE + len(pages)))
    return eligibility.normalize({"requirements": requirements, "routes": list(routes), **rest}, posted, numbers,
                                 dict(zip(numbers, pages)))


def _common(result):
    return result.data["common"]["requirements"]


@pytest.mark.parametrize("written, expected", [
    ("10,800만원", 108_000_000), ("3억 3,700만원", 337_000_000), ("37,080천원", 37_080_000),
    ("1억원", 100_000_000), ("45,420,000원", 45_420_000), ("24,500만원 이하", 245_000_000), ("약 3억", None), ("만원", None),
])
def test_amount_written_in_korean_units_becomes_won(written, expected):
    assert eligibility.amount_krw(written) == expected


def test_numbers_missing_from_the_requirement_text_are_cleared_with_a_warning():
    """모델이 원문 문구에 없는 숫자를 내면(계산·추정) 판정에 쓰지 않는다. 한 숫자라도 실패하면 그 요건의 숫자를 모두 비운다.
    나이 상한만 비우고 하한을 남기면 서버가 상한을 제한 없음으로 읽어 판정이 넓어진다."""
    result = _normalize([
        {"kind": "AGE", "scope": "APPLICANT", "minimum": 19, "maximum": 39, "text": "만 19세 이상 만 40세 이하인 자"},
        {"kind": "INCOME", "scope": "HOUSEHOLD", "standard": "URBAN_WORKER_MONTHLY_INCOME", "year": "전년도", "percent": 70,
         "extraBySize": [{"size": 1, "extra": 20}], "text": "70% 이하(1인 가구 90%)"},
        {"kind": "ASSETS", "scope": "HOUSEHOLD", "amount": "3억 3,700만원", "text": "총자산가액 3억 3,700만원 이하"},
        {"kind": "CAR_VALUE", "scope": "HOUSEHOLD", "amount": "3,708만원", "text": "자동차가액 3,557만원 이하"},
    ])

    age, income, assets, car = _common(result)
    assert (age["minimum"], age["maximum"]) == (None, None)
    assert (income["percent"], income["incomeBaseYear"], income["householdSizeExtraPercent"]) == (
        70, 2025, [{"householdSize": 1, "percent": 20}])
    assert (assets["maximumKrw"], car["maximumKrw"]) == (337_000_000, None)
    # 경고는 JSON 경로가 아니라 요건 문구로 가리킨다.
    assert result.warnings == [
        "신청자격 요건 '만 19세 이상 만 40세 이하인 자': 숫자를 확인하지 못해 직접 확인으로 남깁니다(문구에 없는 숫자(39)).",
        "신청자격 요건 '자동차가액 3,557만원 이하': 숫자를 확인하지 못해 직접 확인으로 남깁니다(문구에 없는 금액(3,708만원)).",
    ]


def test_secondary_numbers_are_checked_against_the_evidence_page_and_amounts_on_a_number_boundary():
    """가구원 수별 최종 비율과 거주 개월은 짧은 요건 문구 대신 근거 쪽 글에서 찾는다. 금액은 10,800만원 안의 800만원처럼 숫자 중간에서 맞추지 않는다."""
    income_page = "월평균소득의 100퍼센트 이하 단, 가구원수가 1인인 경우에는 120퍼센트, 2인인 경우 110퍼센트 총자산가액 10,800만원 이하"
    income = {"kind": "INCOME", "scope": "HOUSEHOLD", "standard": "URBAN_WORKER_MONTHLY_INCOME", "year": "전년도",
              "percent": 100, "text": "월평균소득의 100퍼센트 이하"}

    by_final, residence, assets, wrong_extra = _common(_normalize([
        {**income, "percentBySize": [{"size": 1, "percent": 120}, {"size": 2, "percent": 110}]},
        {"kind": "RESIDENCE", "regions": ["인천광역시"], "months": 24, "text": "인천광역시에 거주", "page": 2},
        {"kind": "ASSETS", "scope": "APPLICANT", "amount": "800만원", "text": "총자산가액 10,800만원 이하"},
        {**income, "extraBySize": [{"size": 1, "extra": 50}]},
    ], pages=[income_page, "인천광역시에 거주 입주자모집공고일 현재 2년 이상 계속 거주"]))

    # 계약의 가구원 수별 값은 기본 비율에 더하는 값이다(120% → 20).
    assert (by_final["percent"], by_final["householdSizeExtraPercent"]) == (
        100, [{"householdSize": 1, "percent": 20}, {"householdSize": 2, "percent": 10}])
    assert residence["minimumMonths"] == 24
    assert assets["maximumKrw"] is None
    # 가구원 수별 최종 비율(150%)이 근거 쪽에 없으면 그 소득 요건의 숫자를 모두 비운다.
    assert (wrong_extra["percent"], wrong_extra["householdSizeExtraPercent"]) == (None, [])


def test_final_percents_by_household_size_without_a_base_use_the_largest_size_as_the_base():
    """원문이 '1인 120%, 2인 110%, 3인 이상 100%'처럼 가구원 수별 비율만 적으면 가장 큰 가구원 수의 비율이 기본 비율이다.
    기본으로 볼 비율이 없으면(1·2인만 있음) 숫자를 비운다."""
    text = "가구원수별 가구당 월평균소득 1인 120%, 2인 110%, 3인 이상 100% 이하"
    sizes = [{"size": 1, "percent": 120}, {"size": 2, "percent": 110}, {"size": 3, "percent": 100}]

    result = _normalize([{"kind": "INCOME", "scope": "HOUSEHOLD", "text": text, "percentBySize": sizes},
                         {"kind": "INCOME", "scope": "HOUSEHOLD", "text": text, "percentBySize": sizes[:2]}])

    with_base, without_base = _common(result)
    assert (with_base["percent"], with_base["householdSizeExtraPercent"]) == (
        100, [{"householdSize": 1, "percent": 20}, {"householdSize": 2, "percent": 10}])
    assert (without_base["percent"], without_base["householdSizeExtraPercent"]) == (None, [])
    assert [warning for warning in result.warnings if "기본 소득 비율이 없는 가구원 수별 비율" in warning]


@pytest.mark.parametrize("written, expected", [("전년도", 2025), ("2026년", 2026), ("당해연도", 2026), ("최근", None)])
def test_income_base_year_is_resolved_from_the_posted_date(written, expected):
    [income] = _common(_normalize([{"kind": "INCOME", "year": written, "text": "소득"}]))

    assert income["incomeBaseYear"] == expected


def test_unknown_kinds_and_nested_any_of_become_other_and_scopes_are_closed():
    other, any_of, no_house, family = _common(_normalize([
        {"kind": "SAVINGS_ACCOUNT", "text": "청약통장 가입"},
        {"kind": "ANY_OF", "text": "대학생 또는 취업준비생", "options": ["r3", "r4", "없는 요건"]},
        {"kind": "UNIVERSITY_STUDENT", "text": "대학 재학"},
        {"kind": "ANY_OF", "text": "중첩", "options": []},
        {"kind": "NO_HOUSE", "scope": "FAMILY", "text": "무주택"},
        {"kind": "FAMILY_TYPE", "familyTypes": ["COUPLE_OR_ENGAGED", "DIVORCED", "UNMARRIED"], "text": "혼인 중이 아닐 것"},
    ]))

    assert other == {"kind": "OTHER", "asWritten": "청약통장 가입"}
    # 선택 요건(r3, r4)은 ANY_OF 안에만 있고 공고 전체 요건으로 따로 나오지 않는다.
    # 중첩된 선택 요건(r4)과 응답에 없는 선택 요건은 빼지 않고 직접 확인(OTHER)으로 남긴다.
    assert [option["kind"] for option in any_of["options"]] == ["UNIVERSITY_STUDENT", "OTHER", "OTHER"]
    assert no_house["scope"] is None
    assert family["familyTypes"] == ["UNMARRIED", "COUPLE_OR_ENGAGED"]


def test_requirements_shared_by_every_route_become_common_and_the_rest_stay_with_their_route():
    """모델은 요건을 한 번만 쓰고 경로가 id로 가리킨다. 모든 경로가 가리키는 요건은 공고 전체(common), 나머지는 그 경로의 요건이 되고
    근거 쪽은 첨부 순번에서 원본 쪽 번호로 바뀐다. 경로 요약은 그 경로 첫 근거와 함께 공급대상 신청 조건이 된다."""
    result = _normalize(
        [{"kind": "NO_HOUSE", "scope": "HOUSEHOLD", "text": "무주택세대구성원"},
         {"kind": "AGE", "scope": "APPLICANT", "minimum": 65, "text": "만 65세 이상", "page": 2},
         {"kind": "UNIVERSITY_STUDENT", "text": "대학에 재학 중인 사람", "page": 3}],
        routes=[{"name": "고령자", "requires": ["r1", "r2"], "anyOfQualifications": ["q1"], "summary": "65세 이상"},
                {"name": "취업준비생", "supplyTarget": "대학생", "requires": ["r1", "r3"], "summary": "재학 중"}],
        qualifications=[{"id": "q1", "name": "주거급여 수급자", "law": "「주거급여법」", "text": "주거급여 수급자", "page": 2}],
        exclusions=[{"title": "재당첨 제한", "body": "공공임대 당첨자", "page": 3}, {"title": "", "body": "제목 없음", "page": 3}],
        pages=["무주택세대구성원", "만 65세 이상 주거급여 수급자", "대학에 재학 중인 사람 공공임대 당첨자"])

    assert [requirement["kind"] for requirement in _common(result)] == ["NO_HOUSE"]
    assert result.data["common"]["summary"] is None
    assert [(target["target"], target["supplyTarget"], [item["kind"] for item in target["requirements"]])
            for target in result.data["targets"]] == [("고령자", "고령자", ["AGE"]), ("취업준비생", "대학생", ["UNIVERSITY_STUDENT"])]
    assert result.data["targets"][0]["qualifications"] == [
        {"exactName": "주거급여 수급자", "legalBasis": "「주거급여법」", "conditionsAsWritten": "주거급여 수급자"}]
    assert result.data["exclusions"] == [{"title": "재당첨 제한", "body": "공공임대 당첨자"}]
    assert [(item["path"], item["page"], item["quote"]) for item in result.evidence] == [
        ("eligibility.common.requirements[0]", 11, "무주택세대구성원"),
        ("eligibility.targets[0].requirements[0]", 12, "만 65세 이상"),
        ("eligibility.targets[0].qualifications[0]", 12, "주거급여 수급자"),
        ("eligibility.targets[1].requirements[0]", 13, "대학에 재학 중인 사람"),
        ("eligibility.exclusions[0]", 13, "공공임대 당첨자")]
    # 하위 경로(취업준비생)의 요약은 그 경로가 속한 공급표 대상(대학생)에 붙는다.
    assert result.conditions == [{"target": "고령자", "condition": "65세 이상"}, {"target": "대학생", "condition": "재학 중"}]
    assert [(item["path"], item["page"]) for item in result.condition_evidence] == [
        ("applicationConditions[0]", 12), ("applicationConditions[1]", 13)]
    assert result.warnings == []


def test_a_single_unnamed_route_applies_to_the_whole_announcement_and_a_single_named_route_keeps_its_target():
    requirements = [{"kind": "NO_HOUSE", "scope": "HOUSEHOLD", "text": "무주택세대구성원"}]
    qualifications = [{"id": "q1", "name": "생계급여 수급자", "text": "생계급여 수급자", "page": 1}]

    whole = _normalize(requirements, [{"requires": ["r1"], "anyOfQualifications": ["q1"], "summary": "무주택 수급자"}],
                       qualifications=qualifications)
    single = _normalize(requirements, [{"name": "고령자", "requires": ["r1"], "summary": "무주택 고령자"}])

    assert whole.data["targets"] == [] and whole.data["common"]["summary"] == "무주택 수급자"
    assert [item["exactName"] for item in whole.data["common"]["qualifications"]] == ["생계급여 수급자"]
    assert whole.conditions == [{"target": None, "condition": "무주택 수급자"}]
    # 경로가 하나여도 이름이 있으면 그 대상의 요건이다. 공고 전체로 올리면 공급표의 다른 대상에도 적용된다.
    assert _common(single) == [] and [item["kind"] for item in single.data["targets"][0]["requirements"]] == ["NO_HOUSE"]
    assert single.conditions == [{"target": "고령자", "condition": "무주택 고령자"}]


def test_unlinked_and_unknown_items_are_dropped_with_readable_warnings():
    """경로가 가리키지 않은 요건·자격, 이름 없는 경로·자격은 빼고 알린다. 경로가 가리킨 요건이 응답에 없으면 그 경로가
    남은 요건만으로 충족되지 않게 직접 확인 요건(OTHER)을 남긴다."""
    result = _normalize(
        [{"kind": "NO_HOUSE", "scope": "HOUSEHOLD", "text": "무주택세대구성원"}, {"kind": "OTHER", "text": "청약통장 가입"}],
        routes=[{"name": "청년", "requires": ["r1", "r9"], "anyOfQualifications": ["q2"]}, {"name": "고령자", "requires": ["r1"]},
                {"name": " ", "requires": ["r1"], "summary": "버림"}],
        qualifications=[{"id": "q1", "name": "차상위계층", "text": "차상위계층", "page": 1},
                        {"id": "q2", "name": " ", "text": "빈 자격", "page": 1}])

    assert [target["target"] for target in result.data["targets"]] == ["청년", "고령자"]
    assert result.data["targets"][0]["qualifications"] == [] and len(_common(result)) == 1
    assert result.data["targets"][0]["requirements"] == [
        {"kind": "OTHER", "asWritten": "모델 응답에서 빠진 요건이 있습니다. 공고문에서 신청자격을 확인해 주세요."}]
    assert result.data["targets"][1]["requirements"] == []
    assert not [item for item in result.evidence if item["path"].startswith("eligibility.targets[0].requirements")]
    assert result.warnings == [
        "신청자격: 이름이 없는 신청 경로 1개를 뺐습니다.",
        "신청자격: 경로가 가리킨 요건(r9)이 응답에 없어 직접 확인으로 남겼습니다.",
        "신청자격: 경로가 가리킨 직접 확인 자격(q2)에 이름이 없어 뺐습니다.",
        "신청자격: 어느 신청 경로에도 쓰이지 않은 요건 1개를 뺐습니다('청약통장 가입').",
        "신청자격: 어느 신청 경로에도 쓰이지 않은 직접 확인 자격 1개를 뺐습니다('차상위계층').",
    ]


def test_adult_age_option_numbers_and_amount_suffix_are_checked_against_their_own_text():
    """민법상 성년자는 숫자 없이도 19세다. 선택 요건은 제 문구와 근거로, 금액의 '이하'는 떼고 대조한다."""
    result = _normalize([
        {"kind": "AGE", "scope": "APPLICANT", "minimum": 19, "text": "민법상 성년자인 무주택세대구성원"},
        {"kind": "ANY_OF", "text": "6세 이하 자녀(태아포함)를 둔 자", "options": ["r3", "r4"]},
        {"kind": "MARRIAGE_PERIOD", "maximum": 7, "text": "혼인기간이 7년 이내일 것", "page": 2},
        {"kind": "AGE", "scope": "YOUNGEST_CHILD", "maximum": 6, "text": "6세 이하 자녀(태아포함)를 둔 자"},
        {"kind": "ASSETS", "scope": "HOUSEHOLD", "amount": "24,500만원 이하", "text": "총자산가액 24,500만원 이하"},
    ], pages=["민법상 성년자인 무주택세대구성원 6세 이하 자녀(태아포함)를 둔 자 총자산가액 24,500만원 이하", "혼인기간이 7년 이내일 것"])

    adult, any_of, assets = _common(result)
    assert adult["minimum"] == 19
    assert [option.get("maximum") for option in any_of["options"]] == [7, 6]
    assert assets["maximumKrw"] == 245_000_000
    assert result.warnings == []
    assert ("eligibility.common.requirements[1].options[0]", 12, "혼인기간이 7년 이내일 것") in [
        (item["path"], item["page"], item["quote"]) for item in result.evidence]


@pytest.mark.parametrize("amount, text", [
    # 표기가 달라도 같은 금액이면 인정한다. 공고문이 빈칸 양식처럼 적어도 같은 금액이다.
    ("24,500만원", "총자산가액 245,000,000원 이하"), ("24,500만원", "총 자산 가액 합산 기준 ( 24,500 )만원 이하"),
    ("2억 4,500만원", "총자산 24,500만원 이하"),
])
def test_amount_is_compared_by_value_with_the_amounts_written_in_the_text(amount, text):
    [assets] = _common(_normalize([{"kind": "ASSETS", "scope": "HOUSEHOLD", "amount": amount, "text": text}]))

    assert (assets["maximumKrw"], assets["amountAsWritten"]) == (245_000_000, amount)


def test_text_that_is_not_in_the_source_leaves_the_requirement_without_evidence_and_numbers():
    """요건 문구가 원문 표기와 근거 인용을 겸하므로, 문구의 낱말이 원문 쪽 글에 모두 있어야 근거가 붙고 숫자가 남는다.
    모델이 문구와 숫자를 함께 지어내면 문구 안의 숫자 대조만으로는 걸리지 않는다. 근거가 없는 요건은 서버가 직접 확인으로 둔다."""
    pages = ["만 19세 이상 39세 이하인 사람", "총자산 3억 3,700만원 이하"]

    result = _normalize([
        {"kind": "AGE", "scope": "APPLICANT", "minimum": 19, "maximum": 39, "text": "만 19세 이상 39세 이하인 사람"},
        {"kind": "AGE", "scope": "APPLICANT", "minimum": 19, "maximum": 45, "text": "만 19세 이상 45세 이하인 사람"},
        {"kind": "NO_HOUSE", "scope": "HOUSEHOLD", "text": "무주택세대구성원", "page": 9},
    ], pages=pages)

    on_page, invented, out_of_range = _common(result)
    assert (on_page["minimum"], on_page["maximum"]) == (19, 39)
    assert (invented["minimum"], invented["maximum"], invented["asWritten"]) == (None, None, "만 19세 이상 45세 이하인 사람")
    assert [item["path"] for item in result.evidence] == ["eligibility.common.requirements[0]"]
    assert result.warnings == [
        "신청자격 요건 '만 19세 이상 45세 이하인 사람': 근거 쪽에서 이 문구를 찾지 못해 직접 확인으로 남깁니다.",
        "신청자격 요건 '무주택세대구성원': 근거 쪽에서 이 문구를 찾지 못해 직접 확인으로 남깁니다.",
    ]
    assert out_of_range == {"kind": "NO_HOUSE", "asWritten": "무주택세대구성원", "scope": "HOUSEHOLD"}


def test_evidence_page_is_the_page_the_text_is_on_when_the_model_cites_another_page():
    """모델은 첨부 순번 대신 원본 쪽 번호를 적거나 옆 쪽을 가리키기도 한다(공주옥룡 실측: 첨부 7쪽의 순위 한정 문구를 원본 쪽 번호 8로 적음).
    문구가 원문에 있으면 실제로 있는 쪽을 근거 쪽으로 쓰고 숫자를 남긴다. 가리킨 쪽에도 있으면 가리킨 쪽이 먼저다."""
    pages = ["무주택세대구성원", "총자산 3억 3,700만원 이하\n금회 모집은 1순위에 한합니다", "무주택세대구성원 유지"]

    result = _normalize(
        [{"kind": "ASSETS", "scope": "HOUSEHOLD", "amount": "3억 3,700만원", "text": "총자산 3억 3,700만원 이하", "page": 1},
         {"kind": "NO_HOUSE", "scope": "HOUSEHOLD", "text": "무주택세대구성원", "page": 3}],
        routes=[{"name": "가. 수급자", "rank": 1, "requires": ["r1", "r2"]}, {"name": "타. 일반", "rank": 2, "requires": ["r1", "r2"]}],
        rankLimit={"ranks": [1], "text": "금회 모집은 1순위에 한합니다", "page": 12}, pages=pages)

    assert [target["target"] for target in result.data["targets"]] == ["가. 수급자"]
    assert result.data["targets"][0]["requirements"][0]["maximumKrw"] == 337_000_000
    assert [(item["path"], item["page"]) for item in result.evidence] == [
        ("eligibility.targets[0].requirements[0]", _FIRST_SOURCE_PAGE + 1), ("eligibility.targets[0].requirements[1]", _FIRST_SOURCE_PAGE + 2)]


def test_text_is_found_on_the_page_across_line_breaks_cell_order_and_middle_dot_variants():
    """PDF 글은 줄바꿈·칸 순서·가운뎃점 글자가 모델이 옮긴 문구와 다를 수 있어 낱말마다, 가운뎃점은 같은 글자로 보고 찾는다."""
    [requirement] = _common(_normalize(
        [{"kind": "OTHER", "text": "생계·의료급여 수급자 선정기준의 소득인정액 이하"}],
        pages=["소득인정액 이하\n수급자 선정기준의 생계ㆍ의료급여\n수급자"]))

    assert requirement["asWritten"] == "생계·의료급여 수급자 선정기준의 소득인정액 이하"


def test_text_found_across_line_breaks_cell_order_and_middle_dot_variants_gets_evidence_without_a_warning():
    result = _normalize([{"kind": "OTHER", "text": "생계·의료급여 수급자 선정기준의 소득인정액 이하"}],
                        pages=["소득인정액 이하\n수급자 선정기준의 생계ㆍ의료급여\n수급자"])

    assert result.evidence == [{"path": "eligibility.common.requirements[0]", "page": _FIRST_SOURCE_PAGE,
                                "quote": "생계·의료급여 수급자 선정기준의 소득인정액 이하"}]
    assert result.warnings == []


@pytest.mark.parametrize("ranks, text, page, kept", [
    # 한정 문구가 원문 쪽에 있고 순위 숫자가 그 문구에 있을 때만 다른 순위 경로를 뺀다.
    ([1], "금회 모집은 1순위에 한합니다", "금회 모집은 1순위에 한합니다", ["가. 수급자", "순위 없는 경로"]),
    ([1], "금회 모집은 1순위에 한합니다", "입주자 선정 순위 안내", ["가. 수급자", "타. 일반", "순위 없는 경로"]),
    ([1], "금회 모집 대상자", "금회 모집 대상자", ["가. 수급자", "타. 일반", "순위 없는 경로"]),
    # 문구가 더 많은 순위를 받는다고 적었으면(모델이 ranks를 덜 씀) 받는 순위의 경로를 빼지 않는다.
    ([1], "금회 모집은 1순위 및 2순위에 한합니다", "금회 모집은 1순위 및 2순위에 한합니다", ["가. 수급자", "타. 일반", "순위 없는 경로"]),
    # 순위 숫자가 있어도 한정하는 문장이 아니면(순위 설명) 빼지 않는다.
    ([1], "1순위 : 해당 주택이 건설되는 지역에 거주하는 자", "1순위 : 해당 주택이 건설되는 지역에 거주하는 자",
     ["가. 수급자", "타. 일반", "순위 없는 경로"]),
    ([1], "1순위 해당자만 신청 가능", "1순위 해당자만 신청 가능", ["가. 수급자", "순위 없는 경로"]),
    # 한정에 맞는 경로가 하나도 없으면(모델이 순위를 잘못 붙임) 모두 남긴다.
    ([3], "금회 모집은 3순위에 한합니다", "금회 모집은 3순위에 한합니다", ["가. 수급자", "타. 일반", "순위 없는 경로"]),
])
def test_routes_of_ranks_not_recruited_are_dropped_only_when_the_limit_is_on_the_page(ranks, text, page, kept):
    """모집하지 않는 순위의 경로가 남으면 신청할 수 없는 사람에게 지원 가능으로 보인다. 모델이 한정을 잘못 읽어 정상 경로를 빼는 것은 더 나쁘므로,
    한정 문구를 원문에서 확인한 때만 뺀다."""
    result = _normalize(
        [{"kind": "NO_HOUSE", "scope": "HOUSEHOLD", "text": "무주택세대구성원"}, {"kind": "OTHER", "text": "일반 입주자"}],
        routes=[{"name": "가. 수급자", "rank": 1, "requires": ["r1"]}, {"name": "타. 일반", "rank": 2, "requires": ["r1", "r2"]},
                {"name": "순위 없는 경로", "requires": ["r1"]}],
        rankLimit={"ranks": ranks, "text": text, "page": 1}, pages=["무주택세대구성원 일반 입주자 " + page])

    assert [target["target"] for target in result.data["targets"]] == kept
    dropped = "타. 일반" not in kept
    assert ("신청자격: 이번 모집은 1순위만 받아 다른 순위 경로를 뺐습니다(타. 일반)." in result.warnings) is dropped
    # 뺀 경로만 쓰던 요건은 '쓰이지 않은 요건' 경고를 내지 않는다.
    assert not [warning for warning in result.warnings if "어느 신청 경로에도" in warning]


def test_reply_without_the_requirement_and_route_lists_is_a_failed_stage():
    result = eligibility.normalize({"data": {}}, "2026-09-30", [1], {1: ""})

    assert result.data is None and result.warnings == ["신청자격을 읽지 못했습니다: AI 모델 응답의 신청자격 형식이 맞지 않습니다."]


def test_reply_without_requirements_yields_empty_groups():
    result = _normalize([])

    assert result.data == {"common": {"requirements": [], "qualifications": [], "summary": None},
                           "targets": [], "exclusions": []}
    assert (result.evidence, result.conditions, result.warnings) == ([], [], [])


def test_sub_route_summary_reaches_its_supply_target_condition_through_merge():
    """하위 경로(대학생 계층의 취업준비생)의 요약은 그 경로가 속한 공급표 대상(대학생)의 신청 조건에 붙는다."""
    from ai_worker import merge

    extracted = _normalize([{"kind": "JOB_SEEKER", "text": "졸업 또는 중퇴한 지 2년 이내"}], routes=[
        {"name": "취업준비생", "supplyTarget": "대학생", "requires": ["r1"], "summary": "졸업·중퇴 2년 이내 취업준비생"}])
    supply = {"data": {"complexes": [], "unresolvedFields": [], "supplyRows": [
        {"complexReference": {"sourceComplexName": "인천석남"}, "targets": [{"target": "대학생", "applicationCondition": None}]}]},
        "sharedCounts": [], "notApplicable": [], "evidence": []}
    notice = {"data": {"announcement": {}, "receptionPlaces": [], "complexes": [], "schedules": [], "unresolvedFields": []},
              "notApplicable": [], "evidence": []}

    tree, _ = merge.merge(supply, notice, extracted)

    assert tree["data"]["supplyRows"][0]["targets"][0]["applicationCondition"] == "졸업·중퇴 2년 이내 취업준비생"
    assert tree["data"]["eligibility"]["targets"][0]["supplyTargets"] == ["대학생"]
    assert [item["page"] for item in tree["evidence"] if item["path"].endswith(".applicationCondition")] == [11]


def test_closed_lists_in_the_prompt_match_the_code():
    """지시문과 코드의 닫힌 목록이 다르면 한쪽 값이 조용히 OTHER(직접 확인)나 빈 값으로 바뀌므로 같아야 한다."""
    prompt = (files("ai_worker") / "prompts" / "announcement-pdf-eligibility.txt").read_text(encoding="utf-8")

    written = set(re.findall(r"\b[A-Z][A-Z_]{2,}\b", prompt)) - {"JSON"}

    assert written == (eligibility.KINDS | eligibility.SCOPES | eligibility.INCOME_STANDARDS
                       | set(eligibility.FAMILY_TYPES) | eligibility.ADJUSTMENTS)


@pytest.mark.parametrize("months, page, regions", [
    (18, "인천광역시에 1년 6개월 이상 계속 거주", ["인천광역시"]),
    (18, "1순위 인천광역시 거주자 2년 이상", []),
    # 쪽 글의 맨 숫자('1순위')나 합친 표기의 일부('1년 6개월'의 1년·6개월)로는 통과하지 않는다.
    (12, "1순위 인천광역시 거주자 2년 이상", []),
    (12, "인천광역시에 1년 6개월 이상 계속 거주", []),
    (6, "인천광역시에 1년 6개월 이상 계속 거주", []),
    ("24", "인천광역시 2년 이상 거주", []),
])
def test_unverified_residence_months_clear_the_regions_too(months, page, regions):
    """거주기간을 대조하지 못하면 지역도 비운다. 개월만 비우면 서버가 '지역 거주'만 판정해 넓어진다.
    쪽 글은 단위가 붙은 형태(1년 6개월)만 인정하고, '1순위' 같은 숫자로 통과시키지 않는다."""
    [residence] = _common(_normalize(
        [{"kind": "RESIDENCE", "regions": ["인천광역시"], "months": months, "text": "인천광역시"}], pages=[page]))

    assert residence["regions"] == regions
    assert residence["minimumMonths"] == (months if regions else None)


def test_extra_percent_without_a_unit_on_the_page_and_an_amount_inside_eok_are_not_accepted():
    """쪽 글에 단위 없는 숫자(120세대)만 있으면 가구원 수별 비율로 인정하지 않고, '3억 3,700만원' 안의 '3,700만원'도 맞추지 않는다."""
    income, assets = _common(_normalize([
        {"kind": "INCOME", "scope": "HOUSEHOLD", "standard": "URBAN_WORKER_MONTHLY_INCOME", "year": "전년도",
         "percent": 100, "extraBySize": [{"size": 1, "extra": 20}], "text": "월평균소득의 100퍼센트 이하"},
        {"kind": "ASSETS", "scope": "HOUSEHOLD", "amount": "3,700만원", "text": "총자산 3억 3,700만원 이하"},
    ], pages=["월평균소득의 100퍼센트 이하 총자산 3억 3,700만원 이하 1순위 120세대 공급"]))

    assert (income["percent"], income["householdSizeExtraPercent"]) == (None, [])
    assert assets["maximumKrw"] is None


def test_household_size_extras_written_as_added_points_on_another_eligibility_page_are_accepted():
    """통합공공임대는 요건 문장에 '1~2인 가구는 우대비율을 가산'이라고만 적고 가산값('세대원수 우대비율 : 1인 20%p, 2인 10%p')은
    기준표 쪽에 적는다. 최종 비율(120%)이 요건 쪽에 없어도 '가구원 수 + 가산값' 짝이 신청자격 쪽 어디든 있으면 인정한다.
    출산자녀 가산('2인 이상 20%p, 1인 10%p')은 가구원 수 가산이 아니므로 그 숫자로는 인정하지 않는다."""
    income = {"kind": "INCOME", "scope": "HOUSEHOLD", "standard": "STANDARD_MEDIAN_INCOME", "year": "2026년", "percent": 100,
              "text": "월평균소득이 기준 중위소득의 100퍼센트(1~2인 가구 및 출산가구의 경우에는 우대비율을 가산) 이하인 사람", "page": 2}
    criteria = "① 세대원수 우대비율 : 1인 20%p, 2인 10%p 가산\n② 출산자녀수 우대비율 : 2인 이상 20%p, 1인 10%p 가산"

    by_size, by_children, child_only, per_person = _common(_normalize([
        {**income, "extraBySize": [{"size": 1, "extra": 20}, {"size": 2, "extra": 10}]},
        {**income, "extraBySize": [{"size": 1, "extra": 10}, {"size": 2, "extra": 20}]},
        # 출산자녀 줄의 '1인 10%p'만 옮겨도 가구원 1인 가산으로 인정하지 않는다.
        {**income, "extraBySize": [{"size": 1, "extra": 10}]},
        {**income, "extraBySize": [{"size": 1, "extra": 20}]},
    ], pages=[criteria, income["text"]]))

    assert (by_size["percent"], by_size["householdSizeExtraPercent"]) == (
        100, [{"householdSize": 1, "percent": 20}, {"householdSize": 2, "percent": 10}])
    assert (by_children["percent"], by_children["householdSizeExtraPercent"]) == (None, [])
    assert (child_only["percent"], child_only["householdSizeExtraPercent"]) == (None, [])
    assert per_person["householdSizeExtraPercent"] == [{"householdSize": 1, "percent": 20}]


def test_household_size_extra_written_with_the_word_household_is_accepted_without_a_label():
    """'가구원수별 가산비율 : 1인 가구 20%p'처럼 짝 안에 '가구'가 적힌 표기도 가구원 수 가산이다."""
    [income] = _common(_normalize([
        {"kind": "INCOME", "scope": "HOUSEHOLD", "percent": 70, "extraBySize": [{"size": 1, "extra": 20}],
         "text": "월평균소득의 70% 이하", "page": 2},
    ], pages=["가산비율 : 1인 가구 20%p, 2인 가구 10%p", "월평균소득의 70% 이하"]))

    assert income["householdSizeExtraPercent"] == [{"householdSize": 1, "percent": 20}]


def test_base_percent_taken_from_percents_by_size_must_be_in_the_requirement_text():
    """기본 비율은 주 숫자라 요건 문구 안에 있어야 한다. 같은 쪽의 다른 대상 비율(100%)로 통과하면 70% 대상이 100%로 판정된다."""
    [income] = _common(_normalize([
        {"kind": "INCOME", "scope": "HOUSEHOLD", "text": "가구원수별 가구당 월평균소득 기준 이하",
         "percentBySize": [{"size": 1, "percent": 120}, {"size": 2, "percent": 110}, {"size": 3, "percent": 100}]},
    ], pages=["가구원수별 가구당 월평균소득 기준 이하\n청년: 100% 이하 (1인 120%, 2인 110%)\n고령자: 70% (1인 90%, 2인 80%)"]))

    assert (income["percent"], income["householdSizeExtraPercent"]) == (None, [])


def test_asset_and_car_amounts_written_on_one_line_are_not_swapped():
    """통합공공임대는 '총자산 …원 및 자동차 …원 이하' 한 줄이 두 요건의 문구다. 금액이 둘이면 그 요건의 낱말 바로 뒤 금액만 인정한다."""
    text = "자산기준 : 총자산 34,500만원 및 자동차 4,563만원 이하"

    assets, car, swapped_assets, swapped_car = _common(_normalize([
        {"kind": "ASSETS", "scope": "HOUSEHOLD", "amount": "34,500만원", "text": text},
        {"kind": "CAR_VALUE", "scope": "HOUSEHOLD", "amount": "4,563만원", "text": text},
        {"kind": "ASSETS", "scope": "HOUSEHOLD", "amount": "4,563만원", "text": text},
        {"kind": "CAR_VALUE", "scope": "HOUSEHOLD", "amount": "34,500만원", "text": text},
    ]))

    assert (assets["maximumKrw"], car["maximumKrw"]) == (345_000_000, 45_630_000)
    assert (swapped_assets["maximumKrw"], swapped_car["maximumKrw"]) == (None, None)


def test_amount_cut_out_of_a_larger_amount_on_the_page_is_cleared():
    """모델이 '3억 3,700만원'을 '3,700만원'으로 줄여 옮기면 낱말은 쪽에 있어도 그 금액은 원문에 없다."""
    cut, whole, blank_form = _common(_normalize([
        {"kind": "ASSETS", "scope": "HOUSEHOLD", "amount": "3,700만원", "text": "총자산 3,700만원 이하"},
        {"kind": "ASSETS", "scope": "HOUSEHOLD", "amount": "3억 3,700만원", "text": "총자산 3억 3,700만원 이하"},
        {"kind": "CAR_VALUE", "scope": "HOUSEHOLD", "amount": "4,542만원", "text": "자동차가액 ( 4,542 )만원 이하"},
    ], pages=["총자산 3억\n3,700만원 이하 자동차가액 ( 4,542 )만원 이하"]))

    assert (cut["maximumKrw"], whole["maximumKrw"], blank_form["maximumKrw"]) == (None, 337_000_000, 45_420_000)


def test_an_option_not_found_on_the_page_turns_the_whole_choice_into_a_direct_check():
    """서버는 ANY_OF의 선택 요건마다 근거를 보지 않는다. 원문에 없는 선택 요건을 kind 그대로 두면 그 선택으로 자동 판정된다.
    서버가 조건으로 바꾸지 못하는 OTHER로 두어 ANY_OF 전체가 직접 확인이 되게 한다."""
    [choice] = _common(_normalize([
        {"kind": "ANY_OF", "options": ["r2", "r3"], "text": "다음의 어느 하나에 해당하는 사람"},
        {"kind": "MARRIAGE_PERIOD", "maximum": 7, "text": "혼인기간이 7년 이내인 사람"},
        {"kind": "UNIVERSITY_STUDENT", "text": "대학에 재학 중인 사람"},
    ], pages=["다음의 어느 하나에 해당하는 사람\n혼인기간이 7년 이내인 사람"]))

    assert [option["kind"] for option in choice["options"]] == ["MARRIAGE_PERIOD", "OTHER"]


def test_only_requirements_shared_by_every_unnamed_route_are_announcement_wide():
    """이름 없는 경로가 둘 이상이면 경로를 나눌 수 없어 뺀다. 그때 경로마다 다른 요건(19~39세, 65세 이상)을 공고 전체 요건으로
    합치면 아무도 충족하지 못한다. 모든 경로가 가리킨 요건만 남기고, 뺀 요건이 있으면 공통 요건만으로 충족되지 않게
    직접 확인 요건(OTHER)을 둔다."""
    result = _normalize(
        [{"kind": "AGE", "scope": "APPLICANT", "minimum": 19, "maximum": 39, "text": "만 19세 이상 39세 이하"},
         {"kind": "AGE", "scope": "APPLICANT", "minimum": 65, "text": "만 65세 이상"},
         {"kind": "NO_HOUSE", "scope": "HOUSEHOLD", "text": "무주택세대구성원"}],
        routes=[{"requires": ["r1", "r3"]}, {"requires": ["r2", "r3"]}],
        qualifications=[{"id": "q1", "name": "수급자", "text": "생계급여 수급자"}])

    assert [item["kind"] for item in _common(result)] == ["NO_HOUSE", "OTHER"]
    assert _common(result)[1]["asWritten"] == "신청 대상마다 다른 요건이 있습니다. 공고문에서 신청자격을 확인해 주세요."
    assert result.data["common"]["qualifications"] == [] and result.data["targets"] == []

    same = _normalize([{"kind": "NO_HOUSE", "scope": "HOUSEHOLD", "text": "무주택세대구성원"}],
                      routes=[{"requires": ["r1"]}, {"requires": ["r1"]}])
    # 경로마다 다른 요건이 없으면 직접 확인을 더하지 않는다.
    assert [item["kind"] for item in _common(same)] == ["NO_HOUSE"]
    assert "신청자격: 이름이 없는 신청 경로 2개를 뺐습니다." in result.warnings


def test_words_ending_in_won_are_not_amounts_and_minor_is_not_adult():
    """'세대구성원', '전원'의 '원'은 금액이 아니다. '미성년'은 성년(19세)이 아니라 숫자 없는 나이로 인정하지 않는다."""
    result = _normalize([
        {"kind": "ASSETS", "scope": "HOUSEHOLD", "amount": "34,500만원", "text": "총자산 가액이 세대구성원 전원 합산 34,500만원 이하"},
        {"kind": "AGE", "scope": "APPLICANT", "minimum": 19, "text": "미성년 자녀"},
    ])

    assets, minor = _common(result)
    assert assets["maximumKrw"] == 345_000_000
    assert minor["minimum"] is None
    assert len(result.warnings) == 1 and "미성년 자녀" in result.warnings[0]


def test_any_of_with_an_option_missing_from_the_reply_becomes_a_direct_check():
    """선택 요건 하나가 응답에 없으면 남은 선택지만으로 판정하지 않게 OTHER 선택 요건으로 채운다(서버가 ANY_OF 전체를 직접 확인으로 둔다)."""
    result = _normalize([
        {"kind": "ANY_OF", "text": "다음 중 하나", "options": ["r2", "r9"]},
        {"kind": "MARRIAGE_PERIOD", "maximum": 7, "text": "혼인기간이 7년 이내일 것"},
    ])

    assert [option["kind"] for option in _common(result)[0]["options"]] == ["MARRIAGE_PERIOD", "OTHER"]
    assert "신청자격: 선택 요건(r9)이 응답에 없어 직접 확인으로 남겼습니다." in result.warnings
