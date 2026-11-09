from ai_worker import merge


def test_complex_paths_are_remapped_before_stage_evidence_is_combined():
    supply = {
        "data": {
            "announcement": {}, "receptionPlaces": [], "schedules": [], "unresolvedFields": [
                {"path": "complexes[0].parkingCount", "reason": "공급 단계 확인 필요"}],
            "complexes": [{"sourceComplexName": "Alpha", "totalHouseholdCount": 100, "parkingCount": None}],
            "supplyRows": [{"complexReference": {"sourceComplexName": "Alpha"}, "targets": []}],
        },
        "sharedCounts": [],
        "notApplicable": [{"path": "complexes[0].address", "reason": "공급 단계 주소 없음"}],
        "evidence": [{"path": "complexes[0]", "page": 1, "quote": "Alpha"}],
        "sourceRows": [],
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

    tree, _ = merge.merge(supply, notice)

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
            "supplyRows": [{"complexReference": {"sourceComplexName": "Alpha"}, "targets": []}],
            "unresolvedFields": [],
        },
        "sharedCounts": [], "notApplicable": [], "evidence": [], "sourceRows": [],
    }
    notice = {
        "data": {"announcement": {}, "receptionPlaces": [], "complexes": [], "schedules": [], "unresolvedFields": []},
        "notApplicable": [], "evidence": [],
    }

    tree, _ = merge.merge(supply, notice)

    assert {item["path"] for item in tree["data"]["unresolvedFields"]} >= {
        "complexes[0].address", "complexes[0].totalHouseholdCount", "complexes[0].parkingCount",
    }


def test_three_conflicting_complex_values_remain_unresolved():
    complexes = [
        {"sourceComplexName": "Alpha", "address": "Road", "totalHouseholdCount": None, "parkingCount": None},
        {"sourceComplexName": "Alpha", "address": "Other Road", "totalHouseholdCount": None, "parkingCount": None},
        {"sourceComplexName": "Alpha", "address": "Third Road", "totalHouseholdCount": None, "parkingCount": None},
    ]

    supply = {"data": {"complexes": [], "supplyRows": [], "unresolvedFields": []},
              "sharedCounts": [], "notApplicable": [], "evidence": [], "sourceRows": []}
    notice = {"data": {"announcement": {}, "receptionPlaces": [], "complexes": complexes, "schedules": [],
                       "unresolvedFields": []}, "notApplicable": [], "evidence": []}

    tree, warnings = merge.merge(supply, notice)

    assert len(tree["data"]["complexes"]) == 1 and tree["data"]["complexes"][0]["address"] is None
    reason = "표·본문 단계의 값이 달라 미확인으로 남김"
    assert [item["path"] for item in tree["data"]["unresolvedFields"] if item["reason"] == reason] == [
        "complexes[0].address"]
    assert warnings == [f"complexes[0].address: {reason}"]


def test_notice_address_with_lot_suffixes_is_the_same_as_the_supply_table_address():
    supply = {"data": {"complexes": [{"sourceComplexName": "Alpha", "address": "서울특별시 중구 세종대로 110"}],
                       "supplyRows": [{"complexReference": {"sourceComplexName": "Alpha"}, "targets": []}], "unresolvedFields": []},
              "sharedCounts": [], "notApplicable": [], "evidence": [], "sourceRows": []}
    notice = {"data": {"announcement": {}, "receptionPlaces": [], "schedules": [], "unresolvedFields": [],
                       "complexes": [{"sourceComplexName": "Alpha", "address": "서울시 중구 세종대로 110 일원"}]},
              "notApplicable": [], "evidence": []}

    tree, warnings = merge.merge(supply, notice)

    assert tree["data"]["complexes"][0]["address"] == "서울특별시 중구 세종대로 110"
    assert warnings == []


def _condition_stages(conditions, targets, evidence):
    supply = {"data": {"complexes": [], "unresolvedFields": [], "supplyRows": [
        {"complexReference": {"sourceComplexName": "평택송화"}, "targets": targets}]},
        "sharedCounts": [], "notApplicable": [], "evidence": []}
    notice = {"data": {"announcement": {}, "receptionPlaces": [], "complexes": [], "schedules": [], "unresolvedFields": [],
                       "applicationConditions": conditions}, "notApplicable": [], "evidence": evidence}
    return supply, notice


def test_announcement_wide_and_target_conditions_fill_each_target_condition_with_evidence():
    """공고 전체 신청자격(무주택세대구성원·소득자산)과 대상별 조건(65세 이상)을 대상 신청 조건에 합친다. 대상 이름은 바꾸지 않는다."""
    supply, notice = _condition_stages(
        [{"target": None, "condition": "무주택세대구성원, 소득·자산 기준 충족"}, {"target": "고령자", "condition": "65세 이상"}],
        [{"target": "전체", "applicationCondition": None}, {"target": "고령자(주거약자)", "applicationCondition": "주거약자용"}],
        [{"path": "applicationConditions[0]", "page": 4, "quote": "무주택세대구성원으로서"},
         {"path": "applicationConditions[1]", "page": 5, "quote": "65세 이상"}])

    tree, _ = merge.merge(supply, notice)

    targets = tree["data"]["supplyRows"][0]["targets"]
    assert [target["target"] for target in targets] == ["전체", "고령자(주거약자)"]
    assert [target["applicationCondition"] for target in targets] == [
        "무주택세대구성원, 소득·자산 기준 충족", "무주택세대구성원, 소득·자산 기준 충족 · 65세 이상 · 주거약자용"]
    assert "applicationConditions" not in tree["data"]
    assert {(item["path"], item["page"]) for item in tree["evidence"] if item["path"].endswith("applicationCondition")} == {
        ("supplyRows[0].targets[0].applicationCondition", 4), ("supplyRows[0].targets[1].applicationCondition", 4)}


def test_long_conditions_are_cut_at_a_separator_within_255_characters():
    supply, notice = _condition_stages(
        [{"target": None, "condition": "가" * 200}, {"target": None, "condition": "나" * 100}],
        [{"target": "청년", "applicationCondition": None}], [])

    tree, _ = merge.merge(supply, notice)

    assert tree["data"]["supplyRows"][0]["targets"][0]["applicationCondition"] == "가" * 200


def test_missing_or_broken_conditions_leave_targets_as_they_are():
    supply, notice = _condition_stages("잘못된 값", [{"target": "청년", "applicationCondition": "대학생 제외"}], [])

    tree, _ = merge.merge(supply, notice)

    assert tree["data"]["supplyRows"][0]["targets"][0]["applicationCondition"] == "대학생 제외"


def test_table_condition_is_kept_when_conditions_exceed_255_characters():
    supply, notice = _condition_stages(
        [{"target": None, "condition": "가" * 150}, {"target": None, "condition": "나" * 120}],
        [{"target": "고령자(주거약자)", "applicationCondition": "주거약자용"}], [])

    tree, _ = merge.merge(supply, notice)

    condition = tree["data"]["supplyRows"][0]["targets"][0]["applicationCondition"]
    assert condition.endswith("주거약자용") and len(condition) <= 255 and condition.startswith("가" * 150)


def test_target_conditions_attach_only_to_the_same_target_core_name():
    """'신혼부부' 조건은 예비신혼부부에, '청년 창업인' 조건은 청년에 붙지 않는다. 괄호 꼬리(주거약자)와 · 로 묶은 대상은 같은 대상이다."""
    supply, notice = _condition_stages(
        [{"target": "신혼부부", "condition": "혼인 7년 이내"}, {"target": "청년 창업인", "condition": "창업 7년 이내"},
         {"target": "고령자", "condition": "65세 이상"}],
        [{"target": "예비신혼부부", "applicationCondition": None}, {"target": "청년", "applicationCondition": None},
         {"target": "신혼부부·한부모가족", "applicationCondition": None}, {"target": "고령자(주거약자)", "applicationCondition": None}],
        [])

    tree, _ = merge.merge(supply, notice)

    assert [target["applicationCondition"] for target in tree["data"]["supplyRows"][0]["targets"]] == [
        None, None, "혼인 7년 이내", "65세 이상"]
