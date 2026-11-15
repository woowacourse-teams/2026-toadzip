from ai_worker import merge
from ai_worker.eligibility import Extracted
from ai_worker.supply_sheet import Entry, SupplySheet, Unit


def complex_sheet(*names):
    """단지명만 있는 공급표 모델."""
    return SupplySheet([Unit(name, None, {}) for name in names])


def test_complex_paths_are_remapped_before_stage_evidence_is_combined():
    supply = {
        "data": {
            "announcement": {}, "receptionPlaces": [], "schedules": [], "unresolvedFields": [
                {"path": "complexes[0].parkingCount", "reason": "공급 단계 확인 필요"}],
            "complexes": [{"sourceComplexName": "Alpha", "totalHouseholdCount": 100, "parkingCount": None}],
        },
        "notApplicable": [{"path": "complexes[0].address", "reason": "공급 단계 주소 없음"}],
        "evidence": [{"path": "complexes[0]", "page": 1, "quote": "Alpha"}],
    }
    notice = {
        "data": {
            "announcement": {"name": "Sample"}, "receptionPlaces": [], "schedules": [], "unresolvedFields": [
                {"path": "complexes[0].parkingCount", "reason": "본문 확인 필요"}],
            "complexes": [
                {"sourceComplexName": "Beta", "address": "Beta Road", "totalHouseholdCount": 50, "parkingCount": 10},
                {"sourceComplexName": "Alpha", "address": "Alpha Road", "totalHouseholdCount": 100, "parkingCount": 20},
            ],
        },
        "notApplicable": [],
        "evidence": [
            {"path": "complexes[0]", "page": 1, "quote": "Beta"},
            {"path": "complexes[1]", "page": 1, "quote": "Alpha"},
        ],
    }

    tree, _ = merge.merge(supply, complex_sheet("Alpha"), notice)

    assert tree["data"]["complexes"][0]["sourceComplexName"] == "Alpha"
    assert tree["data"]["complexes"][1]["sourceComplexName"] == "Beta"
    assert {(item["path"], item["quote"]) for item in tree["evidence"]} == {
        ("complexes[0]", "Alpha"), ("complexes[1]", "Beta"),
    }
    assert {item["path"] for item in tree["notApplicable"]} == {"complexes[0].address"}


def test_missing_complex_values_are_reported_as_unresolved():
    supply = {
        "data": {
            "complexes": [{"sourceComplexName": "Alpha", "totalHouseholdCount": None, "parkingCount": None}],
            "unresolvedFields": [],
        },
        "notApplicable": [], "evidence": [],
    }
    notice = {
        "data": {"announcement": {}, "receptionPlaces": [], "complexes": [], "schedules": [], "unresolvedFields": []},
        "notApplicable": [], "evidence": [],
    }

    tree, _ = merge.merge(supply, complex_sheet("Alpha"), notice)

    assert {item["path"] for item in tree["data"]["unresolvedFields"]} >= {
        "complexes[0].address", "complexes[0].totalHouseholdCount", "complexes[0].parkingCount",
    }


def test_three_conflicting_complex_values_remain_unresolved():
    complexes = [
        {"sourceComplexName": "Alpha", "address": "Road", "totalHouseholdCount": None, "parkingCount": None},
        {"sourceComplexName": "Alpha", "address": "Other Road", "totalHouseholdCount": None, "parkingCount": None},
        {"sourceComplexName": "Alpha", "address": "Third Road", "totalHouseholdCount": None, "parkingCount": None},
    ]

    supply = {"data": {"complexes": [], "unresolvedFields": []}, "notApplicable": [], "evidence": []}
    notice = {"data": {"announcement": {}, "receptionPlaces": [], "complexes": complexes, "schedules": [],
                       "unresolvedFields": []}, "notApplicable": [], "evidence": []}

    tree, warnings = merge.merge(supply, SupplySheet(), notice)

    assert len(tree["data"]["complexes"]) == 1 and tree["data"]["complexes"][0]["address"] is None
    reason = "표·본문 단계의 값이 달라 미확인으로 남김"
    assert [item["path"] for item in tree["data"]["unresolvedFields"] if item["reason"] == reason] == [
        "complexes[0].address"]
    assert warnings == [f"complexes[0].address: {reason}"]


def test_notice_address_with_lot_suffixes_is_the_same_as_the_supply_table_address():
    supply = {"data": {"complexes": [{"sourceComplexName": "Alpha", "address": "서울특별시 중구 세종대로 110"}],
                       "unresolvedFields": []},
              "notApplicable": [], "evidence": []}
    notice = {"data": {"announcement": {}, "receptionPlaces": [], "schedules": [], "unresolvedFields": [],
                       "complexes": [{"sourceComplexName": "Alpha", "address": "서울시 중구 세종대로 110 일원"}]},
              "notApplicable": [], "evidence": []}

    tree, warnings = merge.merge(supply, complex_sheet("Alpha"), notice)

    assert tree["data"]["complexes"][0]["address"] == "서울특별시 중구 세종대로 110"
    assert warnings == []


def _condition_stages(conditions, targets, evidence):
    """신청자격 단계 요약(conditions)과 그 근거를 merge가 받는 형태(Extracted)로 만든다. targets는 (표기, 표의 신청 조건)이다."""
    supply = {"data": {"complexes": [], "unresolvedFields": []}, "notApplicable": [], "evidence": []}
    sheet = SupplySheet([Unit("평택송화", "26A", {}, [Entry(label, {"applicationCondition": condition})
                                                    for label, condition in targets])])
    notice = {"data": {"announcement": {}, "receptionPlaces": [], "complexes": [], "schedules": [], "unresolvedFields": []},
              "notApplicable": [], "evidence": []}
    data = {"common": {"requirements": [], "qualifications": [], "summary": None}, "targets": [], "exclusions": []}
    items = conditions if isinstance(conditions, list) else []
    return supply, sheet, notice, Extracted(data, [], items, evidence)


def emitted_targets(sheet):
    return [(target["label"], target["applicationCondition"]) for target in sheet.emit(None)["targets"]]


def test_announcement_wide_and_target_conditions_fill_each_target_condition_with_evidence():
    """공고 전체 신청자격(무주택세대구성원·소득자산)과 대상별 조건(65세 이상)을 대상 신청 조건에 합친다. 대상 이름은 바꾸지 않는다."""
    supply, sheet, notice, extracted = _condition_stages(
        [{"target": None, "condition": "무주택세대구성원, 소득·자산 기준 충족"}, {"target": "고령자", "condition": "65세 이상"}],
        [("전체", None), ("고령자(주거약자)", "주거약자용")],
        [{"path": "applicationConditions[0]", "page": 4, "quote": "무주택세대구성원으로서"},
         {"path": "applicationConditions[1]", "page": 5, "quote": "65세 이상"}])

    tree, _ = merge.merge(supply, sheet, notice, extracted)

    assert emitted_targets(sheet) == [
        ("전체", "무주택세대구성원, 소득·자산 기준 충족"),
        ("고령자(주거약자)", "무주택세대구성원, 소득·자산 기준 충족 · 65세 이상 · 주거약자용")]
    assert "applicationConditions" not in tree["data"]
    assert [(item["path"], item["page"]) for item in sheet.emit(None)["evidence"]] == [
        ("targets[0].applicationCondition", 4), ("targets[1].applicationCondition", 4)]


def test_long_conditions_are_cut_at_a_separator_within_255_characters():
    supply, sheet, notice, extracted = _condition_stages(
        [{"target": None, "condition": "가" * 200}, {"target": None, "condition": "나" * 100}],
        [("청년", None)], [])

    _tree, _ = merge.merge(supply, sheet, notice, extracted)

    assert emitted_targets(sheet) == [("청년", "가" * 200)]


def test_missing_or_broken_conditions_leave_targets_as_they_are():
    supply, sheet, notice, extracted = _condition_stages("잘못된 값", [("청년", "대학생 제외")], [])

    _tree, _ = merge.merge(supply, sheet, notice, extracted)

    assert emitted_targets(sheet) == [("청년", "대학생 제외")]


def test_table_condition_is_kept_when_conditions_exceed_255_characters():
    supply, sheet, notice, extracted = _condition_stages(
        [{"target": None, "condition": "가" * 150}, {"target": None, "condition": "나" * 120}],
        [("고령자(주거약자)", "주거약자용")], [])

    _tree, _ = merge.merge(supply, sheet, notice, extracted)

    [(_, condition)] = emitted_targets(sheet)
    assert condition.endswith("주거약자용") and len(condition) <= 255 and condition.startswith("가" * 150)


def test_target_conditions_attach_only_to_the_same_target_core_name():
    """'신혼부부' 조건은 예비신혼부부에, '청년 창업인' 조건은 청년에 붙지 않는다. 괄호 꼬리(주거약자)와 · 로 묶은 대상은 같은 대상이다."""
    supply, sheet, notice, extracted = _condition_stages(
        [{"target": "신혼부부", "condition": "혼인 7년 이내"}, {"target": "청년 창업인", "condition": "창업 7년 이내"},
         {"target": "고령자", "condition": "65세 이상"}],
        [("예비신혼부부", None), ("청년", None),
         ("신혼부부·한부모가족", None), ("고령자(주거약자)", None)],
        [])

    _tree, _ = merge.merge(supply, sheet, notice, extracted)

    assert [condition for _, condition in emitted_targets(sheet)] == [None, None, "혼인 7년 이내", "65세 이상"]


def test_eligibility_targets_carry_the_supply_target_names_matched_by_the_same_rule():
    """신청자격 대상에는 대상 신청 조건과 같은 규칙으로 맞춘 공급표 대상 이름을 원문 그대로 싣는다. 맞는 대상이 없으면 빈 목록이다."""
    supply, sheet, notice, _ = _condition_stages([], [
        ("예비신혼부부", None), ("신혼부부·한부모가족", None),
        ("고령자(주거약자)", None), ("대학생", None)], [])
    group = {"requirements": [], "qualifications": [], "summary": None}
    data = {"common": dict(group), "exclusions": [], "targets": [
        {"target": "신혼부부", "supplyTarget": "신혼부부", **group}, {"target": "고령자", "supplyTarget": "고령자", **group},
        {"target": "청년 창업인", "supplyTarget": "청년 창업인", **group},
        {"target": "취업준비생", "supplyTarget": "대학생", **group}]}

    tree, _ = merge.merge(supply, sheet, notice, Extracted(data))

    # 공급 표 대상 안에서 나뉜 경로(취업준비생)는 그 경로가 속한 공급 표 대상(대학생)으로 잇는다.
    assert [target["supplyTargets"] for target in tree["data"]["eligibility"]["targets"]] == [
        ["신혼부부·한부모가족"], ["고령자(주거약자)"], [], ["대학생"]]


def test_qualified_eligibility_paths_link_only_the_supply_target_named_by_their_qualifier():
    """세종 4-2생활권 M3: 공급 표 대상이 신혼부부·한부모가족과 신혼부부·한부모가족(주거약자용)으로 나뉘고, 모델은 주거약자용 경로의
    소속 대상도 괄호 없이 적는다. 경로 이름에 괄호 표기(주거약자용)가 든 경로는 그 대상에만, 나머지 경로는 그 대상을 빼고 잇는다.
    괄호 표기를 가진 경로가 없으면(주거약자용 ↔ 주거약자용(주거복지동)) 지금처럼 모두 잇는다."""
    supply, sheet, notice, _ = _condition_stages([], [
        ("신혼부부·한부모가족", None), ("신혼부부·한부모가족(주거약자용)", None),
        ("주거약자용", None), ("주거약자용(주거복지동)", None)], [])
    group = {"requirements": [], "qualifications": [], "summary": None}
    data = {"common": dict(group), "exclusions": [], "targets": [
        {"target": "신혼부부", "supplyTarget": "신혼부부·한부모가족", **group},
        {"target": "주거약자용 한부모가족", "supplyTarget": "신혼부부·한부모가족", **group},
        {"target": "주거약자용", "supplyTarget": "주거약자용", **group}]}

    tree, _ = merge.merge(supply, sheet, notice, Extracted(data))

    assert [target["supplyTargets"] for target in tree["data"]["eligibility"]["targets"]] == [
        ["신혼부부·한부모가족"], ["신혼부부·한부모가족(주거약자용)"], ["주거약자용", "주거약자용(주거복지동)"]]


def test_eligibility_paths_whose_supply_target_name_matches_nothing_link_by_groups():
    """울산다운2: 공급표 대상은 공백으로 나열한 '신혼부부 예비신혼부부 한부모가족'과 그 주거약자용이고, 모델은 경로 소속 대상을
    '신혼부부·한부모가족'으로 적는다. 이름으로 맞는 대상이 없으면 분류로 잇되 주거약자 구분이 같은 대상에만 잇는다.
    분류를 정하지 못한 경로(청년 창업인)는 잇지 않는다."""
    supply, sheet, notice, _ = _condition_stages([], [
        ("신혼부부 예비신혼부부 한부모가족", None), ("신혼부부 예비신혼부부 한부모가족(주거약자용)", None)], [])
    group = {"requirements": [], "qualifications": [], "summary": None}
    data = {"common": dict(group), "exclusions": [], "targets": [
        {"target": "신혼부부", "supplyTarget": "신혼부부·한부모가족", "groups": ["NEWLYWED"], **group},
        {"target": "예비신혼부부", "supplyTarget": "신혼부부·한부모가족", "groups": ["PRE_NEWLYWED"], **group},
        {"target": "주거약자용 신혼부부", "supplyTarget": "신혼부부·한부모가족", "groups": ["NEWLYWED", "HOUSING_VULNERABLE"],
         **group},
        {"target": "청년 창업인", "supplyTarget": "청년 창업인", "groups": [], **group}]}

    tree, _ = merge.merge(supply, sheet, notice, Extracted(data))

    assert [target["supplyTargets"] for target in tree["data"]["eligibility"]["targets"]] == [
        ["신혼부부 예비신혼부부 한부모가족"], ["신혼부부 예비신혼부부 한부모가족"],
        ["신혼부부 예비신혼부부 한부모가족(주거약자용)"], []]


def test_eligibility_path_without_supply_target_links_every_target_even_if_its_name_holds_a_qualifier():
    """소속 대상(supplyTarget)을 적지 않은 경로는 모든 대상에 잇는다. 경로 이름에 괄호 표기(주거약자용)가 들어 있어도 그 대상 하나로
    좁히지 않고, 다른 경로에서 그 대상을 빼지도 않는다."""
    supply, sheet, notice, _ = _condition_stages([], [
        ("신혼부부·한부모가족", None), ("신혼부부·한부모가족(주거약자용)", None)], [])
    group = {"requirements": [], "qualifications": [], "summary": None}
    data = {"common": dict(group), "exclusions": [], "targets": [
        {"target": "주거약자용", "supplyTarget": None, **group},
        {"target": "신혼부부", "supplyTarget": "신혼부부·한부모가족", **group}]}

    tree, _ = merge.merge(supply, sheet, notice, Extracted(data))

    assert [target["supplyTargets"] for target in tree["data"]["eligibility"]["targets"]] == [
        ["신혼부부·한부모가족", "신혼부부·한부모가족(주거약자용)"], ["신혼부부·한부모가족", "신혼부부·한부모가족(주거약자용)"]]
