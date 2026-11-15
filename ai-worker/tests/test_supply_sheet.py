"""공급표의 수량·임대조건·대상을 어떻게 나누고 묶는지(서버 투영과 같은 규칙). 공급 단계 모델의 답과 쪽 표에서 확인한다."""

from ai_worker import pages
from conftest import model_supply, readable
from test_supply_assembly import SHARED_COUNT_TABLE, table_pages


def answer(housing_types, targets, quotas=(), leases=()):
    return {"data": {"housingTypes": list(housing_types), "targets": list(targets), "quotas": list(quotas),
                     "leaseTerms": list(leases)}}


def housing_type(key, name, row_ids, *targets, **fields):
    return {"key": key, "complexName": "Test", "name": name, "rowIds": list(row_ids), "targets": list(targets), **fields}


def winner(count, *members, row):
    return {"kind": "WINNER", "count": count, "members": [list(member) for member in members], "rowIds": [row]}


TARGETS = [{"key": "t1", "label": "청년"}, {"key": "t2", "label": "고령자"}]


def type_line_table(total, *target_rows):
    """주택형 줄에 모집호수 계를, 대상 줄마다 당첨자 수를 적은 표. 대상 줄은 R2부터다."""
    (first, first_count), *rest = target_rows
    return table_pages([["단지명", "주택형", "모집호수 계", "공급대상", "당첨자 수"], ["Test", "26A", total, first, first_count],
                        *([None, None, None, target, count] for target, count in rest)], page=2)


def test_each_target_count_is_its_own_quota_and_amounts_are_a_lease_term():
    selected = table_pages([["단지명", "주택형", "공급대상", "당첨자 수", "예비자", "임대보증금(원)", "월임대료(원)"],
                            ["Test", "26A", "청년", "20", "5", "1,000", "10"],
                            [None, None, "고령자", "3", "", "900", "9"]], page=2)

    result = model_supply(selected, answer(
        [housing_type("h1", "26A", ["P2:T1:R2", "P2:T1:R3"], "t1", "t2")], TARGETS,
        [winner(25, ("h1", "t1"), row="P2:T1:R2"), {"kind": "RESERVE", "count": 5, "members": [["h1", "t1"]],
                                                    "rowIds": ["P2:T1:R2"]},
         winner(3, ("h1", "t2"), row="P2:T1:R3")],
        [{"members": [["h1", "t1"]], "rentalDeposit": 1000, "monthlyRent": 10, "rowIds": ["P2:T1:R2"]},
         {"members": [["h1", "t2"]], "rentalDeposit": 900, "monthlyRent": 9, "rowIds": ["P2:T1:R3"]}]))

    assert [(item["name"], item["complexName"]) for item in result.sheet["housingTypes"]] == [("26A", "Test")]
    # 모집수는 당첨자와 예비자의 합이다.
    assert readable(result.sheet) == {
        "cells": ["26A/청년", "26A/고령자"],
        "quotas": [("WINNER", 25, ["26A/청년"]), ("RESERVE", 5, ["26A/청년"]), ("WINNER", 3, ["26A/고령자"])],
        "leaseTerms": [(1000, 10, ["26A/청년"]), (900, 9, ["26A/고령자"])],
    }


def test_housing_type_count_not_split_by_targets_is_a_quota_shared_by_its_target_cells():
    # 주택형 모집수를 대상별로 나누지 않았으면 그 대상들이 함께 쓴다.
    result = model_supply(type_line_table("30", ("청년", ""), ("고령자", "")), answer(
        [housing_type("h1", "26A", ["P2:T1:R2", "P2:T1:R3"], "t1", "t2")], TARGETS,
        [winner(30, ("h1",), row="P2:T1:R2")]))
    assert readable(result.sheet)["quotas"] == [("WINNER", 30, ["26A/청년", "26A/고령자"])]


def test_count_of_zero_is_a_zero_quota():
    # 모집수 0도 원문에 적힌 수다. 주택형 계도 대상별 수도 0이면 나머지 0 묶음이 하나 더 생긴다.
    # 서버 투영(row_total = 0 AND counted = 0)과 같게 둔다. 대상이 하나뿐이면 나머지를 그 대상 칸 하나에 걸지 않고
    # '전체' 칸을 더해 함께 쓰게 한다(대상 칸 하나에 걸면 그 대상의 수로 읽힌다).
    result = model_supply(type_line_table("0", ("청년", "0")), answer(
        [housing_type("h1", "26A", ["P2:T1:R2"], "t1")], TARGETS,
        [winner(0, ("h1",), row="P2:T1:R2"), winner(0, ("h1", "t1"), row="P2:T1:R2")]))
    assert readable(result.sheet)["quotas"] == [("WINNER", 0, ["26A/청년"]), ("WINNER", 0, ["26A/청년", "26A/전체"])]


def test_types_joined_by_a_merged_count_cell_are_one_housing_type_each_sharing_the_quota_and_the_lease():
    # 49A·49B·49C가 모집 칸 하나(70)와 보증금 칸 하나를 함께 쓴다. 공급표는 주택형마다 하나이고 수량·임대조건은 세 칸이 함께 쓴다.
    table = [[*row, header] for row, header in zip(SHARED_COUNT_TABLE, ("임대보증금(원)", None, "1,000", None, None))]
    selected = table_pages(table, page=2)
    result = model_supply(selected, answer(
        [housing_type(f"h{number}", label, [f"P2:T1:R{number}"], complexName="탑석 센트럴 자이")
         for number, label in ((3, "49A"), (4, "49B"), (5, "49C"))], [],
        leases=[{"members": [["h3"], ["h4"], ["h5"]], "rentalDeposit": 1000, "rowIds": ["P2:T1:R3"]}]))

    assert [(item["name"], item["exclusiveArea"], item["houseTypes"]) for item in result.sheet["housingTypes"]] == [
        ("49A", 49.9285, []), ("49B", 49.9091, []), ("49C", 49.9438, [])]
    every = ["49A/전체", "49B/전체", "49C/전체"]
    assert readable(result.sheet) == {
        "cells": every,
        "quotas": [("WINNER", 70, every), ("RESERVE", 56, every)],
        "leaseTerms": [(1000, None, every)],
    }


def test_joined_type_values_point_at_each_housing_type():
    # 묶인 49B의 면적을 모델이 틀리게 내면 미확인 경로는 묶음이 아니라 49B 주택형을 가리킨다.
    selected = table_pages(SHARED_COUNT_TABLE, page=2)
    result = model_supply(selected, answer(
        [housing_type(f"h{number}", label, [f"P2:T1:R{number}"], complexName="탑석 센트럴 자이", **area)
         for number, label, area in ((3, "49A", {}), (4, "49B", {"exclusiveArea": 49.0}), (5, "49C", {}))], []))

    assert [item["name"] for item in result.sheet["housingTypes"]] == ["49A", "49B", "49C"]
    assert "housingTypes[1].exclusiveArea" in result.unresolved()


def rows(values: dict[str, dict]) -> pages.PromptPages:
    """표 판독기가 행마다 읽은 칸 값. 키는 원문 행 번호(P쪽:T표:R행)다."""
    return pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=sorted({int(key[1:key.index(":")]) for key in values}),
        supply_row_ids=frozenset(values), supply_row_text={key: "\t".join(value.values()) for key, value in values.items()},
        notice_text="body", notice_pages=[1], complex_candidates=["Test"], table_structure_fallback=False,
        ambiguous_supply_tables=False, supply_row_values=values)


def test_targets_are_one_per_label_and_condition_and_equal_amounts_stay_separate_lease_terms():
    values = {f"P2:T1:R{number}": {"complexName": "Test", "housingType": name, "target": "청년", "rentalDeposit": "1,000",
                                   "rentalDepositUnit": "원"}
              for number, name in ((2, "26A"), (3, "36A"), (4, "46A"))}
    values["P2:T1:R5"] = {"complexName": "Test", "housingType": "56A"}
    targets = [{"key": "t1", "label": "청년", "applicationCondition": "미혼"},
               {"key": "t2", "label": " 청년 ", "applicationCondition": "신혼"}, {"key": "t3", "label": None}]

    result = model_supply(rows(values), answer(
        [housing_type("h1", "26A", ["P2:T1:R2"], "t1"), housing_type("h2", "36A", ["P2:T1:R3"], "t1"),
         housing_type("h3", "46A", ["P2:T1:R4"], "t2"), housing_type("h4", "56A", ["P2:T1:R5"], "t3")], targets,
        leases=[{"members": [[key, target]], "rentalDeposit": 1000, "rowIds": [row]}
                for key, target, row in (("h1", "t1", "P2:T1:R2"), ("h2", "t1", "P2:T1:R3"), ("h3", "t2", "P2:T1:R4"))]))

    assert [(item["label"], item["applicationCondition"]) for item in result.sheet["targets"]] == [
        ("청년", "미혼"), ("청년", "신혼"), ("전체", None)]
    # 금액이 같아도 단지·주택형마다 따로 고칠 수 있게 합치지 않는다.
    assert [members for _, _, members in readable(result.sheet)["leaseTerms"]] == [["26A/청년"], ["36A/청년"], ["46A/청년"]]


def test_result_keeps_only_the_supply_sheet_and_paths_outside_it():
    selected = table_pages([["단지명", "주택형", "공급대상", "모집호수"], ["Test", "26A", "청년", "30"]], page=2)

    result = model_supply(selected, answer([housing_type("h1", "26A", ["P2:T1:R2"], "t1")], TARGETS,
                                           [winner(99, ("h1", "t1"), row="P2:T1:R2")]))

    assert "supplyRows" not in result.tree["data"] and "sharedCounts" not in result.tree
    top_paths = [item["path"] for name in ("evidence", "notApplicable") for item in result.tree[name]]
    top_paths += [item["path"] for item in result.tree["data"]["unresolvedFields"]]
    assert not [path for path in top_paths if path.startswith(("supplyRows", "sharedCounts"))]
    # 표와 다른 모집수(99)는 버리고 미확인으로 둔다. 미확인과 근거는 공급표 안의 경로로만 싣는다.
    assert readable(result.sheet)["quotas"] == []
    assert "cells[0].winner" in result.unresolved()
    assert {item["path"] for item in result.sheet["evidence"]} >= {"housingTypes[0]", "cells[0]"}
