from ai_worker import supply_assembly, supply_sheet
from test_supply_assembly import SHARED_COUNT_TABLE, table_pages


def target(name, supply=None, reserve=None, deposit=None, rent=None, condition=None):
    return {"target": name, "supplyHouseholdCount": supply, "reserveCount": reserve, "rentalDeposit": deposit,
            "monthlyRent": rent, "convertedDeposit": None, "applicationCondition": condition}


def row(name, total=None, *targets, complex_name="Test", subtypes=()):
    return {"complexReference": {"sourceComplexName": complex_name}, "sourceHousingTypeName": name, "sourceRow": None,
            "expectedMoveInMonth": None, "totalSupplyHouseholdCount": total, "exclusiveArea": None, "commonArea": None,
            "supplyArea": None, "constructedHouseholdCount": None, "subtypes": list(subtypes), "targets": list(targets)}


def sheet(*rows, shared=(), unresolved=(), evidence=(), eligibility=None, joined=()):
    tree = {"data": {"supplyRows": list(rows), "unresolvedFields": list(unresolved), "eligibility": eligibility},
            "sharedCounts": list(shared), "evidence": list(evidence)}
    supply_sheet.attach(tree, list(joined))
    return tree["data"]["supplySheet"]


def readable(result):
    """키 대신 '주택형/대상' 이름으로 읽는다."""
    types = {item["key"]: item["name"] for item in result["housingTypes"]}
    labels = {item["key"]: item["label"] for item in result["targets"]}

    def members(items):
        return [f"{types[item['housingType']]}/{labels[item['target']]}" for item in items]
    return {
        "cells": members(result["cells"]),
        "quotas": [(quota["kind"], quota["count"], members(quota["members"])) for quota in result["quotas"]],
        "leaseTerms": [(lease["rentalDeposit"], lease["monthlyRent"], members(lease["members"]))
                       for lease in result["leaseTerms"]],
    }


def test_each_target_count_is_its_own_quota_and_amounts_are_a_lease_term():
    result = sheet(row("26A", 23, target("청년", 20, 5, 1000, 10), target("고령자", 3, None, 900, 9)))

    assert [(item["name"], item["complexName"]) for item in result["housingTypes"]] == [("26A", "Test")]
    assert readable(result) == {
        "cells": ["26A/청년", "26A/고령자"],
        # 행 모집수 23은 대상별 수(20 + 3)로 다 나뉘어 나머지 묶음이 없다.
        "quotas": [("WINNER", 20, ["26A/청년"]), ("RESERVE", 5, ["26A/청년"]), ("WINNER", 3, ["26A/고령자"])],
        "leaseTerms": [(1000, 10, ["26A/청년"]), (900, 9, ["26A/고령자"])],
    }


def test_row_count_not_split_by_targets_is_a_quota_shared_by_the_row_and_an_everyone_cell():
    # 대상이 하나뿐인 행의 나머지를 그 대상 칸 하나에 걸면 그 대상의 수로 읽힌다. '전체' 칸을 더해 함께 쓰게 한다.
    assert readable(sheet(row("26A", 30, target("청년", 10))))["quotas"] == [
        ("WINNER", 10, ["26A/청년"]), ("WINNER", 20, ["26A/청년", "26A/전체"])]
    # 대상이 여럿이면 '전체' 칸 없이 그 대상들이 나머지를 함께 쓴다.
    assert readable(sheet(row("26A", 30, target("청년"), target("고령자"))))["quotas"] == [
        ("WINNER", 30, ["26A/청년", "26A/고령자"])]
    # 대상별 수의 합이 행 모집수보다 크면 행 모집수를 버린다(서버 투영과 같다).
    assert readable(sheet(row("26A", 5, target("청년", 10))))["quotas"] == [("WINNER", 10, ["26A/청년"])]


def test_row_without_target_column_has_one_everyone_cell():
    result = readable(sheet(row("16A", 78)))

    assert result["cells"] == ["16A/전체"]
    assert result["quotas"] == [("WINNER", 78, ["16A/전체"])]


def test_counts_shared_by_targets_or_rows_are_one_quota_with_many_members():
    # 여러 행이 함께 쓰는 모집수에 든 행은 그 행의 나머지 묶음을 따로 두지 않는다(두 번 세지 않는다).
    rows = (row("26A", 20, target("청년", 4), target("고령자", 2)), row("36A", 20, target("신혼부부", 5)))
    result = readable(sheet(*rows, shared=[
        {"paths": ["supplyRows[0].targets[0].reserveCount", "supplyRows[0].targets[1].reserveCount"], "count": 12},
        {"paths": ["supplyRows[0].totalSupplyHouseholdCount", "supplyRows[1].totalSupplyHouseholdCount"], "count": 40},
    ]))

    assert result["quotas"] == [
        ("RESERVE", 12, ["26A/청년", "26A/고령자"]),
        ("WINNER", 40, ["26A/청년", "26A/고령자", "36A/신혼부부"]),
        ("WINNER", 4, ["26A/청년"]), ("WINNER", 2, ["26A/고령자"]), ("WINNER", 5, ["36A/신혼부부"]),
    ]


def test_shared_count_with_mixed_or_unknown_kinds_is_not_a_quota():
    rows = (row("26A", None, target("청년"), target("고령자")),)
    result = sheet(*rows, shared=[
        {"paths": ["supplyRows[0].targets[0].reserveCount", "supplyRows[0].targets[1].supplyHouseholdCount"], "count": 3},
        {"paths": ["supplyRows[9].targets[0].reserveCount"], "count": 3},
        {"paths": ["supplyRows[0].targets[0].reserveCount"], "count": None},
    ])

    assert result["quotas"] == []


def test_rows_joined_by_a_merged_count_cell_become_one_housing_type_each_sharing_the_quota():
    # 49A·49B·49C가 모집 칸 하나(70)를 함께 쓴다. 옛 결과는 공급행 하나(49)였지만 공급표는 주택형마다 하나다.
    selected = table_pages(SHARED_COUNT_TABLE, page=2)
    items = [{"rowIds": [f"P2:T1:R{number}"], "complexName": "탑석 센트럴 자이", "housingType": label, "target": "일반"}
             for number, label in ((3, "49A"), (4, "49B"), (5, "49C"))]
    tree, _ = supply_assembly.assemble({"data": {"rows": items}}, selected)
    tree["data"]["supplyRows"][0]["targets"][0]["rentalDeposit"] = 1000

    supply_sheet.attach(tree, tree.pop(supply_assembly.JOINED_ROWS))
    result = tree["data"]["supplySheet"]

    assert [(item["name"], item["exclusiveArea"], item["houseTypes"]) for item in result["housingTypes"]] == [
        ("49A", 49.9285, []), ("49B", 49.9091, []), ("49C", 49.9438, [])]
    every = ["49A/전체", "49B/전체", "49C/전체"]
    assert readable(result) == {
        "cells": every,
        "quotas": [("WINNER", 70, every), ("RESERVE", 56, every)],
        "leaseTerms": [(1000, None, every)],
    }


def test_application_type_over_several_house_types_is_one_housing_type_with_house_types():
    subtypes = [{"name": "84A", "exclusiveArea": 84.98, "commonArea": 20.1, "constructedHouseholdCount": 10},
                {"name": "84B", "exclusiveArea": 84.95, "commonArea": 20.2, "constructedHouseholdCount": 12}]

    result = sheet(row("84", 7, target("일반", 7), subtypes=subtypes))

    [housing_type] = result["housingTypes"]
    assert housing_type["name"] == "84"
    assert housing_type["houseTypes"] == [
        {"name": "84A", "exclusiveArea": 84.98, "commonArea": 20.1, "supplyArea": None, "constructedHouseholdCount": 10},
        {"name": "84B", "exclusiveArea": 84.95, "commonArea": 20.2, "supplyArea": None, "constructedHouseholdCount": 12}]


def test_targets_are_one_per_label_and_condition_and_equal_amounts_stay_separate_lease_terms():
    result = sheet(row("26A", None, target("청년", 1, deposit=1000, condition="미혼")),
                   row("36A", None, target("청년", 2, deposit=1000, condition="미혼")),
                   row("46A", None, target(" 청년 ", 3, deposit=1000, condition="신혼")),
                   row("56A", None, target(None, 4)))

    assert [(item["label"], item["applicationCondition"]) for item in result["targets"]] == [
        ("청년", "미혼"), ("청년", "신혼"), ("", None)]
    # 금액이 같아도 단지·주택형마다 따로 고칠 수 있게 합치지 않는다.
    assert [members for _, _, members in readable(result)["leaseTerms"]] == [["26A/청년"], ["36A/청년"], ["46A/청년"]]


def test_same_target_twice_in_a_row_is_one_cell_with_summed_count_and_the_first_amounts():
    result = readable(sheet(row("26A", None, target("청년", 2, deposit=1000), target("청년", 3, deposit=2000)),
                            row("36A", None, target("청년", 1), target("청년", 1, deposit=3000))))

    assert result["quotas"] == [("WINNER", 5, ["26A/청년"]), ("WINNER", 2, ["36A/청년"])]
    # 칸 하나에 임대조건은 하나다. 서버 투영처럼 그 칸을 만든 첫 대상의 금액만 쓴다(첫 대상에 금액이 없으면 없다).
    assert result["leaseTerms"] == [(1000, None, ["26A/청년"])]


def test_row_count_of_zero_is_a_zero_quota():
    # 모집수 0도 원문에 적힌 수다. 대상별 수가 없으면 0인 묶음으로 둔다(서버 투영과 같다).
    assert readable(sheet(row("26A", 0, target("청년"))))["quotas"] == [("WINNER", 0, ["26A/청년", "26A/전체"])]
    # 대상별 수도 0이면 나머지 0 묶음이 하나 더 생긴다. 서버 투영(row_total = 0 AND counted = 0)과 같게 둔다.
    assert readable(sheet(row("26A", 0, target("청년", 0))))["quotas"] == [
        ("WINNER", 0, ["26A/청년"]), ("WINNER", 0, ["26A/청년", "26A/전체"])]


def test_eligibility_route_carries_keys_of_supply_targets_with_the_same_label():
    eligibility = {"targets": [{"target": "청년", "supplyTargets": ["청 년"]}, {"target": "고령자", "supplyTargets": []},
                               {"target": "신혼부부", "supplyTargets": ["신혼부부"]}, {"target": "기타", "supplyTargets": [" "]}]}
    result = sheet(row("26A", None, target("청년", condition="미혼")), row("36A", None, target("청년", condition="신혼")),
                   row("46A", None, target("고령자")), row("56A", None, target(None)), eligibility=eligibility)

    # 표기가 같은 대상은 신청 조건이 달라도 모두 잇는다(서버 규칙). 이름이 없거나 맞는 대상이 없으면 키도 없다.
    # 표기를 확인하지 못한 대상(빈 표기)에는 공백뿐인 이름도 잇지 않는다.
    assert [route["supplyTargetKeys"] for route in eligibility["targets"]] == [["t0", "t1"], [], [], []]
    assert [item["key"] for item in result["targets"]] == ["t0", "t1", "t2", "t3"]


def test_unresolved_and_evidence_paths_point_at_what_the_supply_row_value_became():
    rows = (row("26A", 30, target("청년", 10, deposit=1000), target("고령자")),)
    unresolved = [{"path": "supplyRows[0].targets[0].supplyHouseholdCount", "reason": "a"},
                  {"path": "supplyRows[0].targets[1].reserveCount", "reason": "b"},
                  {"path": "supplyRows[0].targets[0].rentalDeposit", "reason": "c"},
                  {"path": "supplyRows[0].totalSupplyHouseholdCount", "reason": "d"},
                  {"path": "supplyRows[0].sourceHousingTypeName", "reason": "e"},
                  {"path": "supplyRows[0].targets[1].target", "reason": "f"},
                  {"path": "complexes[0].address", "reason": "g"}]
    evidence = [{"path": "supplyRows[0]", "page": 3, "quote": "row"},
                {"path": "supplyRows[0].targets[0]", "page": 3, "quote": "청년"}]

    result = sheet(*rows, unresolved=unresolved, evidence=evidence)

    assert readable(result)["quotas"] == [("WINNER", 10, ["26A/청년"]), ("WINNER", 20, ["26A/청년", "26A/고령자"])]
    assert [(item["path"], item["reason"]) for item in result["unresolvedFields"]] == [
        ("quotas[0].count", "a"), ("cells[1].reserve", "b"), ("leaseTerms[0].rentalDeposit", "c"),
        ("quotas[1].count", "d"), ("housingTypes[0].name", "e"), ("targets[1].label", "f")]
    assert [(item["path"], item["quote"]) for item in result["evidence"]] == [
        ("housingTypes[0]", "row"), ("cells[0]", "청년"), ("quotas[0]", "청년"), ("quotas[1]", "row"),
        ("leaseTerms[0]", "청년")]


def test_joined_row_subtype_paths_point_at_each_housing_type():
    subtypes = [{"name": "49A", "exclusiveArea": 49.9}, {"name": "49B", "exclusiveArea": 49.8}]
    unresolved = [{"path": "supplyRows[0].subtypes[1].exclusiveArea", "reason": "a"},
                  {"path": "supplyRows[1].subtypes[0].exclusiveArea", "reason": "b"}]

    result = sheet(row("49", 70, target("일반", 70), subtypes=subtypes),
                   row("84", 7, target("일반", 7), subtypes=[{"name": "84A"}]), unresolved=unresolved, joined=[0])

    assert [item["name"] for item in result["housingTypes"]] == ["49A", "49B", "84"]
    assert [item["path"] for item in result["unresolvedFields"]] == [
        "housingTypes[1].exclusiveArea", "housingTypes[2].houseTypes[0].exclusiveArea"]


def test_result_keeps_only_the_supply_sheet_and_items_outside_supply_rows():
    tree = {"data": {"supplyRows": [row("26A", 30, target("청년", 10))],
                     "unresolvedFields": [{"path": "supplyRows[0].totalSupplyHouseholdCount", "reason": "a"},
                                          {"path": "complexes[0].address", "reason": "b"}]},
            "sharedCounts": [], "notApplicable": [{"path": "supplyRows[0].targets[0].reserveCount", "reason": "c"},
                                                  {"path": "announcement.applicationEndDate", "reason": "d"}],
            "evidence": [{"path": "supplyRows[0]", "page": 1, "quote": "26A 30"},
                         {"path": "sharedCounts[0]", "page": 1, "quote": "공통"},
                         {"path": "complexes[0]", "page": 1, "quote": "Test"}]}

    supply_sheet.attach(tree, [])

    assert "supplyRows" not in tree["data"] and "sharedCounts" not in tree
    assert [item["path"] for item in tree["data"]["unresolvedFields"]] == ["complexes[0].address"]
    assert [item["path"] for item in tree["notApplicable"]] == ["announcement.applicationEndDate"]
    assert [item["path"] for item in tree["evidence"]] == ["complexes[0]"]
    assert [item["path"] for item in tree["data"]["supplySheet"]["evidence"]] == ["housingTypes[0]", "quotas[1]"]
