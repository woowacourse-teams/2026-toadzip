import pytest

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


def test_complex_name_without_a_complex_column_must_be_the_nearest_heading_above_its_table():
    # 광주선운2: 블록 제목(■ … A-1블록) 아래 두 표, 그 아래 A-3블록 제목과 두 표. 표에는 단지명 열이 없다.
    # A-3블록 표의 행에 위쪽 A-1블록 이름을 붙인 값은 받지 않는다.
    above_a1 = "2. 임대대상 및 임대조건\n■ 광주선운2 A-1블록 (총 336세대)\n[단지전체현황]\n55A 217\n[금회모집 임대조건]"
    above_a3 = f"{above_a1}\n55A-1 신혼부부· 한부모가족 20\n■ 광주선운2 A-3블록 (총 147세대)\n[금회모집 임대조건]"
    rows = [{"complexReference": {"sourceComplexName": name}, "sourceHousingTypeName": "55B-1",
             "targets": [{"target": "신혼부부·한부모가족", "reserveCount": 20}]}
            for name in ("광주선운2 A-1블록", "광주선운2 A-3블록", "광주선운2 A-1블록")]
    row_ids = ["P4:T2:R5", "P4:T4:R4", "P4:T4:R4"]
    source_rows = {f"supplyRows[{index}]{suffix}": {row_id} for index, row_id in enumerate(row_ids)
                   for suffix in ("", ".targets[0]")}
    source_text = {"P4:T2:R5": "55B-1\t\t-\t20", "P4:T4:R4": "55B-1\t신혼부부· 한부모가족\t-\t20"}
    source_values = {"P4:T2:R5": {"housingType": "55B-1", "target": "신혼부부·한부모가족", "reserveCount": "20"},
                     "P4:T4:R4": {"housingType": "55B-1", "target": "신혼부부·한부모가족", "reserveCount": "20"}}
    evidence = [item for index in range(3) for item in (
        {"path": f"supplyRows[{index}]", "page": 4, "quote": "55B-1"},
        {"path": f"supplyRows[{index}].targets[0]", "page": 4, "quote": "55B-1 20"})]
    unresolved = []

    source_check.check_source_rows(rows, source_rows, source_text, evidence, unresolved, source_values,
                                   headings=source_check.Headings({"P4:T2:R5": above_a1, "P4:T4:R4": above_a3},
                                                                  ("광주선운2 A-1블록", "광주선운2 A-3블록")))

    assert [row["complexReference"]["sourceComplexName"] for row in rows] == ["광주선운2 A-1블록", "광주선운2 A-3블록", ""]
    assert [item["path"] for item in unresolved] == ["supplyRows[2].complexReference.sourceComplexName"]


def test_heading_is_not_used_when_the_row_has_a_complex_cell():
    rows = [{"complexReference": {"sourceComplexName": "광주선운2 A-1블록"}, "sourceHousingTypeName": "55B-1",
             "targets": []}]
    source_values = {"P4:T2:R5": {"complexName": "광주선운2 A-3블록", "housingType": "55B-1"}}
    unresolved = []

    source_check.check_source_rows(rows, {"supplyRows[0]": {"P4:T2:R5"}}, {"P4:T2:R5": "광주선운2 A-3블록\t55B-1"},
                                   [{"path": "supplyRows[0]", "page": 4, "quote": "55B-1"}], unresolved, source_values,
                                   headings=source_check.Headings(
                                       {"P4:T2:R5": "■ 광주선운2 A-3블록\n55A 20\n■ 광주선운2 A-1블록"},
                                       ("광주선운2 A-1블록", "광주선운2 A-3블록")))

    assert rows[0]["complexReference"]["sourceComplexName"] == ""


@pytest.mark.parametrize("name, expected", [
    ("A1", True),
    # A1-1 제목이 위에 있고 A1 제목 아래 표다. 짧은 이름이 긴 이름에 들어 있어도 표에 가장 가깝게 끝나는 이름만 받는다.
    ("A1-1", False),
    # 지구명은 블록 제목 안에 들어 있지만 블록 이름보다 먼저 끝난다.
    ("광주선운2", False),
    # 끝이 같으면 긴 이름(LH 단지명)이 제목이다. 모델이 낸 짧은 블록 이름은 받지 않는다(보수적으로 미확인).
    ("A1블록", False),
])
def test_only_the_name_ending_nearest_the_table_is_its_heading(name, expected):
    heading = "■ 광주선운2 A1-1블록 공급표\n55A 20\n■ 광주선운2 A1블록"
    names = ("광주선운2", "광주선운2 A1-1블록", "광주선운2 A1블록")
    full = {"A1": "광주선운2 A1블록", "A1-1": "광주선운2 A1-1블록", "광주선운2": "광주선운2", "A1블록": "A1블록"}[name]

    assert source_check.nearest_heading(full, heading, names) is expected


def test_two_spellings_of_one_block_count_as_one_heading_candidate():
    # 같은 블록의 두 표기만 찾히고 A-3블록 제목은 후보에 없다. 가까운지 가릴 수 없어 A-3블록 표 행의 A-1블록 이름을 받지 않는다.
    above_a3 = "■ 광주선운2 A-1블록\n55A 20\n■ 광주선운2 A3BL"
    headings = source_check.Headings({"P4:T4:R4": above_a3}, ("A-1블록", "광주선운2 A-1블록"))

    assert not headings.confirms("광주선운2 A-1블록", {"P4:T4:R4"}, {"P4:T4:R4": {"housingType": "55B-1"}})


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
