from ai_worker import source_check


def test_supply_count_not_present_in_referenced_source_row_is_cleared_and_unresolved():
    rows = [{"sourceHousingTypeName": "26A", "targets": [{"target": "청년", "reserveCount": 99}]}]
    source_rows = {"supplyRows[0]": {"P1:T1:R2"}, "supplyRows[0].targets[0]": {"P1:T1:R2"}}
    source_text = {"P1:T1:R2": "Test\t26A\t청년\t20"}
    source_values = {"P1:T1:R2": {"complexName": "Test", "housingType": "26A", "target": "청년", "reserveCount": "20"}}
    evidence = [
        {"path": "supplyRows[0]", "page": 1, "quote": "Test 26A"},
        {"path": "supplyRows[0].targets[0]", "page": 1, "quote": "26A 청년"},
    ]
    unresolved = []

    warnings = source_check.check_source_rows(rows, source_rows, source_text, evidence, unresolved, source_values)

    assert rows[0]["targets"][0]["reserveCount"] is None
    assert unresolved == [{"path": "supplyRows[0].targets[0].reserveCount",
                           "reason": "추출한 수치가 같은 의미의 원문 표 열에서 확인되지 않음"}]
    # 근거는 확인됐으므로 경고는 없고, 값을 비운 사유는 미확인에만 남는다.
    assert warnings == []


def test_supply_count_matching_a_different_source_cell_is_cleared_and_unresolved():
    rows = [{"sourceHousingTypeName": "26A", "targets": [{"target": "청년", "reserveCount": 26}]}]
    source_rows = {"supplyRows[0]": {"P1:T1:R2"}, "supplyRows[0].targets[0]": {"P1:T1:R2"}}
    source_text = {"P1:T1:R2": "Test\t26A\t청년\t20"}
    source_values = {"P1:T1:R2": {"complexName": "Test", "housingType": "26A", "target": "청년", "reserveCount": "20"}}
    evidence = [
        {"path": "supplyRows[0]", "page": 1, "quote": "Test 26A"},
        {"path": "supplyRows[0].targets[0]", "page": 1, "quote": "26A 청년 20"},
    ]
    unresolved = []

    warnings = source_check.check_source_rows(rows, source_rows, source_text, evidence, unresolved, source_values)

    assert rows[0]["targets"][0]["reserveCount"] is None
    assert {"path": "supplyRows[0].targets[0].reserveCount",
            "reason": "추출한 수치가 같은 의미의 원문 표 열에서 확인되지 않음"} in unresolved
    assert warnings == []


def test_non_numeric_supply_values_must_match_their_meaningful_source_cells():
    rows = [{"complexReference": {"sourceComplexName": "Fake"}, "sourceHousingTypeName": "26B",
             "targets": [{"target": "고령자", "reserveCount": 20}]}]
    row_ids = {"P1:T1:R2"}
    source_rows = {"supplyRows[0]": row_ids, "supplyRows[0].targets[0]": row_ids}
    source_text = {"P1:T1:R2": "Test\t26A\t청년\t20"}
    source_values = {"P1:T1:R2": {"complexName": "Test", "housingType": "26A", "target": "청년", "reserveCount": "20"}}
    evidence = [
        {"path": "supplyRows[0]", "page": 1, "quote": "Test 26A"},
        {"path": "supplyRows[0].targets[0]", "page": 1, "quote": "청년 20"},
    ]
    unresolved = []

    source_check.check_source_rows(rows, source_rows, source_text, evidence, unresolved, source_values)

    assert rows[0]["complexReference"]["sourceComplexName"] == ""
    assert rows[0]["sourceHousingTypeName"] is None
    assert rows[0]["targets"][0]["target"] == ""
    assert {item["path"] for item in unresolved} >= {
        "supplyRows[0].complexReference.sourceComplexName", "supplyRows[0].sourceHousingTypeName",
        "supplyRows[0].targets[0].target",
    }


def test_allowed_target_group_notation_normalization_is_preserved():
    rows = [{"complexReference": {"sourceComplexName": "Test"}, "sourceHousingTypeName": "26A",
             "targets": [{"target": "가군", "reserveCount": 20}]}]
    row_ids = {"P1:T1:R2"}
    source_rows = {"supplyRows[0]": row_ids, "supplyRows[0].targets[0]": row_ids}
    source_text = {"P1:T1:R2": "Test\t26A\t「가」군\t20"}
    source_values = {"P1:T1:R2": {
        "complexName": "Test", "housingType": "26A", "target": "「가」군", "reserveCount": "20",
    }}
    evidence = [
        {"path": "supplyRows[0]", "page": 1, "quote": "Test 26A"},
        {"path": "supplyRows[0].targets[0]", "page": 1, "quote": "「가」군 20"},
    ]
    unresolved = []

    source_check.check_source_rows(rows, source_rows, source_text, evidence, unresolved, source_values)

    assert rows[0]["targets"][0]["target"] == "가군"
    assert "supplyRows[0].targets[0].target" not in {item["path"] for item in unresolved}


def test_unsupported_target_evidence_clears_numeric_values_and_marks_each_field_unresolved():
    rows = [{"targets": [{"target": "청년", "reserveCount": 20, "rentalDeposit": 46920000}]}]
    unresolved = []

    warnings = source_check.check_source_rows(rows, {}, {}, [], unresolved)

    # 경고는 근거를 확인하지 못한 경로마다 한 줄이다. 비운 값마다의 사유는 미확인에만 남는다.
    assert warnings == ["supplyRows[0]: 원문 표 행 ID를 확인하지 못함", "supplyRows[0].targets[0]: 원문 표 행 ID를 확인하지 못함"]
    assert rows[0]["targets"][0]["reserveCount"] is None
    assert rows[0]["targets"][0]["rentalDeposit"] is None
    assert {item["path"] for item in unresolved} >= {
        "supplyRows[0].targets[0]", "supplyRows[0].targets[0].reserveCount", "supplyRows[0].targets[0].rentalDeposit",
    }
