from ai_worker import merge


def test_complex_paths_are_remapped_before_stage_evidence_is_combined():
    supply = {
        "data": {
            "announcement": {}, "receptionPlace": {}, "schedules": [], "unresolvedFields": [
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
            "announcement": {"name": "Sample"}, "receptionPlace": {}, "schedules": [], "unresolvedFields": [
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

    tree, _ = merge.merge(supply, notice, {})

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
        "data": {"announcement": {}, "receptionPlace": {}, "complexes": [], "schedules": [], "unresolvedFields": []},
        "notApplicable": [], "evidence": [],
    }

    tree, _ = merge.merge(supply, notice, {})

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
    notice = {"data": {"announcement": {}, "receptionPlace": {}, "complexes": complexes, "schedules": [],
                       "unresolvedFields": []}, "notApplicable": [], "evidence": []}

    tree, warnings = merge.merge(supply, notice, {})

    assert len(tree["data"]["complexes"]) == 1 and tree["data"]["complexes"][0]["address"] is None
    reason = "표·본문 단계의 값이 달라 미확인으로 남김"
    assert [item["path"] for item in tree["data"]["unresolvedFields"] if item["reason"] == reason] == [
        "complexes[0].address"]
    assert warnings == [f"complexes[0].address: {reason}"]
