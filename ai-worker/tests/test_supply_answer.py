from ai_worker import supply_answer


def answer(housing_types, targets, quotas=(), leases=()):
    return {"data": {"housingTypes": list(housing_types), "targets": list(targets), "quotas": list(quotas),
                     "leaseTerms": list(leases)}}


def housing_type(key, name, row_id, *targets, **fields):
    return {"key": key, "complexName": "Test", "name": name, "rowIds": [row_id], "targets": list(targets), **fields}


TARGETS = [{"key": "t1", "label": "청년"}, {"key": "t2", "label": "고령자", "applicationCondition": "만 65세 이상"}]


def test_each_cell_becomes_one_row_with_the_housing_type_and_target_values():
    compact, warnings = supply_answer.rows(answer(
        [housing_type("h1", "24A", "P4:T1:R4", "t1", "t2", exclusiveArea=24.97,
                      houseTypes=[{"name": "24A-1", "exclusiveArea": 24.97}])], TARGETS))

    assert compact == {"data": {"rows": [
        {"rowIds": ["P4:T1:R4"], "complexName": "Test", "housingType": "24A", "exclusiveArea": 24.97,
         "subtypes": [{"name": "24A-1", "exclusiveArea": 24.97}], "target": "청년"},
        {"rowIds": ["P4:T1:R4"], "complexName": "Test", "housingType": "24A", "exclusiveArea": 24.97,
         "subtypes": [{"name": "24A-1", "exclusiveArea": 24.97}], "target": "고령자",
         "applicationCondition": "만 65세 이상"},
    ]}, "sharedCounts": []}
    assert warnings == []


def test_a_count_of_one_cell_stays_on_that_row_and_a_merged_count_is_one_shared_count():
    # 24A·24B 청년이 모집 칸 하나(30)를, 두 주택형의 모든 대상이 예비 칸 하나(20)를 함께 쓰고, 고령자는 주택형마다 모집 칸이 있다.
    compact, _ = supply_answer.rows(answer(
        [housing_type("h1", "24A", "P4:T1:R4", "t1", "t2"), housing_type("h2", "24B", "P4:T1:R5", "t1", "t2")], TARGETS,
        [{"kind": "WINNER", "count": 30, "members": [["h1", "t1"], ["h2", "t1"]], "rowIds": ["P4:T1:R4"]},
         {"kind": "WINNER", "count": 2, "members": [["h1", "t2"]], "rowIds": ["P4:T1:R4"]},
         {"kind": "RESERVE", "count": 20, "members": [["h1"], ["h2"]], "rowIds": ["P4:T1:R4"]}]))

    rows = compact["data"]["rows"]
    assert [(row["housingType"], row["target"], row.get("supplyHouseholdCount")) for row in rows] == [
        ("24A", "청년", None), ("24A", "고령자", 2), ("24B", "청년", None), ("24B", "고령자", None)]
    # 수는 한 번만 싣는다. 대상마다 복제하지 않는다.
    assert not any("reserveCount" in row for row in rows)
    assert compact["sharedCounts"] == [
        {"rowIndexes": [0, 2], "field": "supplyHouseholdCount", "count": 30, "rowIds": ["P4:T1:R4"]},
        {"rowIndexes": [0, 1, 2, 3], "field": "reserveCount", "count": 20, "rowIds": ["P4:T1:R4"]}]


def test_a_count_written_on_the_housing_type_line_is_the_row_count():
    # 대상 구분 없이 주택형 줄에 적힌 모집수는 행 모집수다. 대상 수로 읽지 않는다.
    compact, _ = supply_answer.rows(answer(
        [housing_type("h1", "26", "P2:T1:R4", "t1", "t2"), housing_type("h2", "31", "P2:T1:R5", "t1")], TARGETS,
        [{"kind": "WINNER", "count": 40, "members": [["h1"]], "rowIds": ["P2:T1:R4"]},
         {"kind": "WINNER", "count": 9, "members": [["h2", "t1"]], "rowIds": ["P2:T1:R5"]}]))

    assert [(row["housingType"], row.get("totalSupplyHouseholdCount"), row.get("supplyHouseholdCount"))
            for row in compact["data"]["rows"]] == [("26", 40, None), ("26", 40, None), ("31", 9, 9)]
    assert compact["sharedCounts"] == []


def test_a_count_shared_by_several_housing_types_points_at_each_housing_type_once():
    # 49A·49B·49C가 함께 쓰는 모집 칸 103은 조립이 그 공급행들의 공통 행 모집수로 읽는다.
    compact, _ = supply_answer.rows(answer(
        [housing_type(f"h{at}", name, f"P1:T1:R{at}", "t1", "t2") for at, name in ((2, "49A"), (3, "49B"), (4, "49C"))],
        TARGETS, [{"kind": "WINNER", "count": 103, "members": [["h2"], ["h3"], ["h4"]], "rowIds": ["P1:T1:R2"]}]))

    assert compact["sharedCounts"] == [
        {"rowIndexes": [0, 2, 4], "field": "totalSupplyHouseholdCount", "count": 103, "rowIds": ["P1:T1:R2"]}]


def test_a_merged_amount_reaches_every_member_row_with_the_row_it_is_written_on():
    compact, _ = supply_answer.rows(answer(
        [housing_type("h1", "26A", "P2:T1:R4", "t2"), housing_type("h2", "26B", "P2:T1:R5", "t2")], TARGETS, leases=[
            {"members": [["h1", "t2"], ["h2", "t2"]], "rentalDeposit": 2520000, "monthlyRent": 50160,
             "rowIds": ["P3:T1:R4"]}]))

    assert [(row["rowIds"], row["rentalDeposit"], row["monthlyRent"], "convertedDeposit" in row)
            for row in compact["data"]["rows"]] == [
        (["P2:T1:R4", "P3:T1:R4"], 2520000, 50160, False), (["P2:T1:R5", "P3:T1:R4"], 2520000, 50160, False)]


def test_two_different_amounts_for_one_cell_leave_that_amount_unresolved():
    compact, _ = supply_answer.rows(answer(
        [housing_type("h1", "26A", "P2:T1:R4", "t1")], TARGETS, leases=[
            {"members": [["h1", "t1"]], "rentalDeposit": 1000, "monthlyRent": 50},
            {"members": [["h1", "t1"]], "rentalDeposit": 2000, "monthlyRent": 50}]))

    [row] = compact["data"]["rows"]
    assert (row["rentalDeposit"], row["monthlyRent"]) == (None, 50)
    assert row["unresolved"] == [
        {"field": "rentalDeposit", "reason": "공급 단계 응답이 같은 칸에 서로 다른 값을 줘 미확인으로 남김"}]


def test_a_member_naming_nothing_in_the_answer_is_dropped_with_a_warning():
    compact, warnings = supply_answer.rows(answer(
        [housing_type("h1", "26A", "P2:T1:R4", "t1"), {"name": "키 없는 주택형"}], TARGETS,
        [{"kind": "WINNER", "count": 7, "members": [["h1", "t9"], ["h1", "t1"]]},
         {"kind": "WINNER", "count": 3, "members": [["h8"]]},
         {"kind": "TOTAL", "count": 3, "members": [["h1"]]}]))

    assert [(row["housingType"], row.get("supplyHouseholdCount")) for row in compact["data"]["rows"]] == [("26A", 7)]
    assert warnings == [
        "공급 단계 응답 housingTypes[1]에 key가 없어 그 주택형을 뺐습니다.",
        "공급 단계 응답 quotas[0]의 멤버 ['h1', 't9']가 가리키는 주택형·공급대상이 없어 그 멤버를 뺐습니다.",
        "공급 단계 응답 quotas[1]의 멤버 ['h8']가 가리키는 주택형·공급대상이 없어 그 멤버를 뺐습니다.",
        "공급 단계 응답 quotas[1]의 종류(kind)나 멤버를 읽지 못해 그 수량을 뺐습니다.",
        "공급 단계 응답 quotas[2]의 종류(kind)나 멤버를 읽지 못해 그 수량을 뺐습니다."]


def test_a_housing_type_without_targets_is_one_row_without_a_target_and_keeps_its_count():
    # 대상 열이 없는 표다. 조립이 그 행을 신청자 구분 없는 행('전체')으로 다룬다.
    compact, _ = supply_answer.rows(answer(
        [housing_type("h1", "16A", "P2:T1:R4")], [],
        [{"kind": "WINNER", "count": 78, "members": [["h1"]], "rowIds": ["P2:T1:R4"]}]))

    assert compact["data"]["rows"] == [{"rowIds": ["P2:T1:R4"], "complexName": "Test", "housingType": "16A",
                                        "totalSupplyHouseholdCount": 78}]


def test_a_target_only_named_by_a_member_is_added_after_the_listed_targets():
    compact, _ = supply_answer.rows(answer(
        [housing_type("h1", "26A", "P2:T1:R4", "t1")], TARGETS,
        [{"kind": "RESERVE", "count": 4, "members": [["h1", "t2"]], "rowIds": ["P2:T1:R4"]}]))

    assert [(row["target"], row.get("reserveCount")) for row in compact["data"]["rows"]] == [("청년", None), ("고령자", 4)]


def test_counts_split_into_supply_columns_are_added_into_one_count_per_cell_group():
    # 49번 16A: 모집수를 우선·일반공급 열로 나눈 표. 대학생 우선 19, 청년(소득 구간 둘) 우선 19, 대학생·청년 일반 39(병합), 철거민 1.
    # 26A 고령자는 우선 1, 일반 1이다. 모집수는 두 열의 합이다.
    targets = [{"key": "t1", "label": "대학생"}, {"key": "t2", "label": "청년", "applicationCondition": "소득 없음"},
               {"key": "t3", "label": "청년", "applicationCondition": "소득 있음"}, {"key": "t4", "label": "철거민"},
               {"key": "t5", "label": "고령자"}]
    compact, _ = supply_answer.rows(answer(
        [housing_type("h1", "16A", "P3:T1:R4", "t1", "t2", "t3", "t4"), housing_type("h2", "26A", "P3:T1:R10", "t5")],
        targets,
        [{"kind": "WINNER", "count": 19, "members": [["h1", "t1"]], "rowIds": ["P3:T1:R4"]},
         {"kind": "WINNER", "count": 39, "members": [["h1", "t1"], ["h1", "t2"], ["h1", "t3"]], "rowIds": ["P3:T1:R4"]},
         {"kind": "WINNER", "count": 19, "members": [["h1", "t2"], ["h1", "t3"]], "rowIds": ["P3:T1:R5"]},
         {"kind": "WINNER", "count": 1, "members": [["h1", "t4"]], "rowIds": ["P3:T1:R6"]},
         {"kind": "WINNER", "count": 1, "members": [["h2", "t5"]], "rowIds": ["P3:T1:R10"]},
         {"kind": "WINNER", "count": 1, "members": [["h2", "t5"]], "rowIds": ["P3:T1:R10"]},
         {"kind": "RESERVE", "count": 3, "members": [["h2", "t5"]], "rowIds": ["P3:T1:R10"]}]))

    rows = compact["data"]["rows"]
    assert [(row["target"], row.get("supplyHouseholdCount"), row.get("totalSupplyHouseholdCount"), row.get("reserveCount"))
            for row in rows] == [("대학생", None, None, None), ("청년", None, None, None), ("청년", None, None, None),
                                 ("철거민", 1, None, None), ("고령자", 2, 2, 3)]
    assert compact["sharedCounts"] == [
        {"rowIndexes": [0, 1, 2], "field": "supplyHouseholdCount", "count": 77, "rowIds": ["P3:T1:R4", "P3:T1:R5"]}]


def test_a_housing_type_line_count_is_not_added_to_the_counts_of_its_targets():
    # 표가 주택형 줄의 계 40과 대상별 30·10을 함께 적으면 계는 행 모집수이고 대상 수에 더하지 않는다.
    compact, _ = supply_answer.rows(answer(
        [housing_type("h1", "26", "P2:T1:R4", "t1", "t2")], TARGETS,
        [{"kind": "WINNER", "count": 40, "members": [["h1"]], "rowIds": ["P2:T1:R4"]},
         {"kind": "WINNER", "count": 30, "members": [["h1", "t1"]], "rowIds": ["P2:T1:R4"]},
         {"kind": "WINNER", "count": 10, "members": [["h1", "t2"]], "rowIds": ["P2:T1:R5"]}]))

    assert [(row["totalSupplyHouseholdCount"], row["supplyHouseholdCount"]) for row in compact["data"]["rows"]] == [
        (40, 30), (40, 10)]
    assert compact["sharedCounts"] == []


def test_a_count_that_is_not_an_integer_is_not_added_and_leaves_the_cell_unresolved():
    compact, _ = supply_answer.rows(answer(
        [housing_type("h1", "26A", "P2:T1:R4", "t1")], TARGETS,
        [{"kind": "WINNER", "count": "19", "members": [["h1", "t1"]]}, {"kind": "WINNER", "count": 39, "members": [["h1", "t1"]]}]))

    [row] = compact["data"]["rows"]
    # 그 칸이 주택형의 유일한 칸이라 행 모집수도 같은 이유로 미확인이다.
    assert (row["supplyHouseholdCount"], row["totalSupplyHouseholdCount"]) == (None, None)
    assert [finding["field"] for finding in row["unresolved"]] == ["supplyHouseholdCount", "totalSupplyHouseholdCount"]


def test_a_malformed_answer_drops_the_unreadable_parts_with_warnings_instead_of_failing_the_job():
    # 모델이 멤버를 중첩 목록·숫자로, kind를 목록으로, targets에 객체를 넣어도 작업 전체가 멈추지 않는다.
    compact, warnings = supply_answer.rows(answer(
        [{"key": "h1", "complexName": "Test", "name": "24A", "rowIds": ["P4:T1:R4"], "targets": ["t1", {"key": "t2"}]},
         {"key": "h2", "complexName": "Test", "name": "24B", "rowIds": ["P4:T1:R5"], "targets": "t1"}], TARGETS,
        [{"kind": "WINNER", "count": 30, "members": [[["h1", "t1"]]], "rowIds": ["P4:T1:R4"]},
         {"kind": "WINNER", "count": 2, "members": 3, "rowIds": ["P4:T1:R4"]},
         {"kind": ["WINNER"], "count": 5, "members": [["h1", "t1"]], "rowIds": ["P4:T1:R4"]},
         {"kind": "WINNER", "count": 9, "members": [["h1", "t1"]], "rowIds": ["P4:T1:R4"]}],
        [{"members": [["h1", 1]], "rentalDeposit": 1000, "rowIds": ["P5:T1:R3"]}]))

    assert [(row["housingType"], row.get("target"), row.get("supplyHouseholdCount")) for row in compact["data"]["rows"]] == [
        ("24A", "청년", 9), ("24B", None, None)]
    assert not any("rentalDeposit" in row for row in compact["data"]["rows"])
    assert sum("quotas[0]" in w for w in warnings) == 2 and sum("quotas[1]" in w for w in warnings) == 2 \
        and sum("quotas[2]" in w for w in warnings) == 1 and sum("leaseTerms[0]" in w for w in warnings) == 1
