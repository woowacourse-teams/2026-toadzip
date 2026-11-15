"""공급 단계 모델이 공급표 모양(주택형·공급대상·수량 칸·금액 칸)으로 답하면 쪽 표와 대조해 공급표가 되는 규칙."""

from ai_worker import pages
from conftest import model_supply, readable
from test_supply_assembly import table_pages


def answer(housing_types, targets, quotas=(), leases=()):
    return {"data": {"housingTypes": list(housing_types), "targets": list(targets), "quotas": list(quotas),
                     "leaseTerms": list(leases)}}


def housing_type(key, name, row_ids, *targets, **fields):
    return {"key": key, "complexName": "Test", "name": name, "rowIds": list(row_ids), "targets": list(targets), **fields}


def rows(values: dict[str, dict]) -> pages.PromptPages:
    """표 판독기가 행마다 읽은 칸 값. 키는 원문 행 번호(P쪽:T표:R행)다."""
    return pages.PromptPages(
        rental_rows=0, supply_text="table", supply_pages=sorted({int(key[1:key.index(":")]) for key in values}),
        supply_row_ids=frozenset(values), supply_row_text={key: "\t".join(value.values()) for key, value in values.items()},
        notice_text="body", notice_pages=[1], complex_candidates=["Test"], table_structure_fallback=False,
        ambiguous_supply_tables=False, supply_row_values=values)


TARGETS = [{"key": "t1", "label": "청년"}, {"key": "t2", "label": "고령자", "applicationCondition": "만 65세 이상"}]


def test_each_cell_of_a_housing_type_is_one_target_cell_with_its_label_and_condition():
    selected = rows({"P4:T1:R4": {"complexName": "Test", "housingType": "24A", "target": "청년", "exclusiveArea": "24.97"},
                     "P4:T1:R5": {"complexName": "Test", "housingType": "24A", "target": "고령자"}})

    result = model_supply(selected, answer([housing_type("h1", "24A", ["P4:T1:R4", "P4:T1:R5"], "t1", "t2",
                                                         exclusiveArea=24.97)], TARGETS))

    assert [(item["name"], item["exclusiveArea"]) for item in result.sheet["housingTypes"]] == [("24A", 24.97)]
    assert [(item["label"], item["applicationCondition"]) for item in result.sheet["targets"]] == [
        ("청년", None), ("고령자", "만 65세 이상")]
    assert readable(result.sheet)["cells"] == ["24A/청년", "24A/고령자"]
    assert result.warnings == []


def test_a_count_of_one_cell_stays_its_own_and_a_merged_count_of_several_targets_is_one_quota_of_them():
    # 청년·신혼부부가 세로 병합된 당첨자 칸 하나(30)와 예비자 칸 하나(20)를 함께 쓰고, 고령자는 자기 칸(2)이 있다.
    # 모집수(WINNER)는 당첨자와 예비자의 합이다.
    selected = table_pages([["단지명", "주택형", "공급대상", "당첨자 수", "예비자"],
                            ["Test", "24A", "청년", "30", "20"],
                            [None, None, "신혼부부", None, None],
                            [None, None, "고령자", "2", ""]], page=4)
    targets = [*TARGETS, {"key": "t3", "label": "신혼부부"}]

    result = model_supply(selected, answer(
        [housing_type("h1", "24A", ["P4:T1:R2", "P4:T1:R3", "P4:T1:R4"], "t1", "t3", "t2")], targets,
        [{"kind": "WINNER", "count": 50, "members": [["h1", "t1"], ["h1", "t3"]], "rowIds": ["P4:T1:R2"]},
         {"kind": "WINNER", "count": 2, "members": [["h1", "t2"]], "rowIds": ["P4:T1:R4"]},
         {"kind": "RESERVE", "count": 20, "members": [["h1", "t1"], ["h1", "t3"]], "rowIds": ["P4:T1:R2"]}]))

    # 수는 한 번만 싣는다. 대상마다 복제하지 않는다.
    assert readable(result.sheet)["quotas"] == [
        ("WINNER", 50, ["24A/청년", "24A/신혼부부"]),
        ("RESERVE", 20, ["24A/청년", "24A/신혼부부"]),
        ("WINNER", 2, ["24A/고령자"])]


# 주택형 줄에 모집호수 계를, 대상 줄마다 당첨자 수를 적은 표
TYPE_LINE_TABLE = [["단지명", "주택형", "모집호수 계", "공급대상", "당첨자 수"],
                   ["Test", "26", "40", "청년", "30"],
                   [None, None, None, "고령자", "10"],
                   [None, "31", "9", "청년", "9"]]


def test_a_count_written_on_the_housing_type_line_is_the_count_of_all_its_cells():
    # 대상 구분 없이 주택형 줄에 적힌 모집수는 그 주택형 모든 칸의 수다. 대상 수로 읽지 않는다.
    result = model_supply(table_pages(TYPE_LINE_TABLE, page=2), answer(
        [housing_type("h1", "26", ["P2:T1:R2", "P2:T1:R3"], "t1", "t2"), housing_type("h2", "31", ["P2:T1:R4"], "t1")],
        TARGETS,
        [{"kind": "WINNER", "count": 40, "members": [["h1"]], "rowIds": ["P2:T1:R2"]},
         {"kind": "WINNER", "count": 9, "members": [["h2", "t1"]], "rowIds": ["P2:T1:R4"]}]))

    assert ("WINNER", 40, ["26/청년", "26/고령자"]) in readable(result.sheet)["quotas"]
    assert ("WINNER", 9, ["31/청년"]) in readable(result.sheet)["quotas"]


def test_a_housing_type_line_count_is_not_added_to_the_counts_of_its_targets():
    # 표가 주택형 줄의 계 40과 대상별 30·10을 함께 적으면 계는 그 주택형의 수이고 대상 수에 더하지 않는다.
    selected = table_pages([["단지명", "주택형", "공급대상", "모집호수"], ["Test", "26", "계", "40"],
                            [None, None, "청년", "30"], [None, None, "고령자", "10"]], page=2)

    result = model_supply(selected, answer(
        [housing_type("h1", "26", ["P2:T1:R2", "P2:T1:R3", "P2:T1:R4"], "t1", "t2")], TARGETS,
        [{"kind": "WINNER", "count": 40, "members": [["h1"]], "rowIds": ["P2:T1:R2"]},
         {"kind": "WINNER", "count": 30, "members": [["h1", "t1"]], "rowIds": ["P2:T1:R3"]},
         {"kind": "WINNER", "count": 10, "members": [["h1", "t2"]], "rowIds": ["P2:T1:R4"]}]))

    # 대상 수의 합이 계와 같아 남는 수가 없다.
    assert readable(result.sheet)["quotas"] == [("WINNER", 30, ["26/청년"]), ("WINNER", 10, ["26/고령자"])]


def test_a_merged_amount_reaches_every_member_cell_with_the_row_it_is_written_on():
    selected = rows({
        "P2:T1:R4": {"complexName": "Test", "housingType": "26A", "target": "고령자"},
        "P2:T1:R5": {"complexName": "Test", "housingType": "26B", "target": "고령자"},
        "P3:T1:R4": {"rentalDeposit": "2,520,000", "monthlyRent": "50,160"}})

    result = model_supply(selected, answer(
        [housing_type("h1", "26A", ["P2:T1:R4"], "t2"), housing_type("h2", "26B", ["P2:T1:R5"], "t2")], TARGETS, leases=[
            {"members": [["h1", "t2"], ["h2", "t2"]], "rentalDeposit": 2520000, "monthlyRent": 50160,
             "rowIds": ["P3:T1:R4"]}]))

    # 같은 금액이라도 칸마다 임대조건 하나다.
    assert readable(result.sheet)["leaseTerms"] == [(2520000, 50160, ["26A/고령자"]), (2520000, 50160, ["26B/고령자"])]
    assert [lease["convertedDeposit"] for lease in result.sheet["leaseTerms"]] == [None, None]


def test_two_different_amounts_for_one_cell_leave_that_amount_unresolved():
    selected = rows({"P2:T1:R4": {"complexName": "Test", "housingType": "26A", "target": "청년", "rentalDeposit": "1,000",
                                  "monthlyRent": "50"}})

    result = model_supply(selected, answer(
        [housing_type("h1", "26A", ["P2:T1:R4"], "t1")], TARGETS, leases=[
            {"members": [["h1", "t1"]], "rentalDeposit": 1000, "monthlyRent": 50, "rowIds": ["P2:T1:R4"]},
            {"members": [["h1", "t1"]], "rentalDeposit": 2000, "monthlyRent": 50, "rowIds": ["P2:T1:R4"]}]))

    assert readable(result.sheet)["leaseTerms"] == [(None, 50, ["26A/청년"])]
    assert {"path": "leaseTerms[0].rentalDeposit",
            "reason": "공급 단계 응답이 같은 칸에 서로 다른 값을 줘 미확인으로 남김"} in result.sheet["unresolvedFields"]


def test_a_member_naming_nothing_in_the_answer_is_dropped_with_a_warning():
    selected = rows({"P2:T1:R4": {"complexName": "Test", "housingType": "26A", "target": "청년",
                                  "supplyHouseholdCount": "7"}})

    result = model_supply(selected, answer(
        [housing_type("h1", "26A", ["P2:T1:R4"], "t1"), {"name": "키 없는 주택형"}], TARGETS,
        [{"kind": "WINNER", "count": 7, "members": [["h1", "t9"], ["h1", "t1"]], "rowIds": ["P2:T1:R4"]},
         {"kind": "WINNER", "count": 3, "members": [["h8"]]},
         {"kind": "TOTAL", "count": 3, "members": [["h1"]]}]))

    assert readable(result.sheet)["quotas"] == [("WINNER", 7, ["26A/청년"])]
    assert result.warnings[:5] == [
        "공급 단계 응답 housingTypes[1]에 key가 없어 그 주택형을 뺐습니다.",
        "공급 단계 응답 quotas[0]의 멤버 ['h1', 't9']가 가리키는 주택형·공급대상이 없어 그 멤버를 뺐습니다.",
        "공급 단계 응답 quotas[1]의 멤버 ['h8']가 가리키는 주택형·공급대상이 없어 그 멤버를 뺐습니다.",
        "공급 단계 응답 quotas[1]의 종류(kind)나 멤버를 읽지 못해 그 수량을 뺐습니다.",
        "공급 단계 응답 quotas[2]의 종류(kind)나 멤버를 읽지 못해 그 수량을 뺐습니다."]


def test_a_housing_type_without_targets_is_one_everyone_cell_and_keeps_its_count():
    # 대상 열이 없는 표다. 그 주택형은 신청자 구분 없는 칸('전체') 하나다.
    selected = rows({"P2:T1:R4": {"complexName": "Test", "housingType": "16A", "totalSupplyHouseholdCount": "78"}})

    result = model_supply(selected, answer(
        [housing_type("h1", "16A", ["P2:T1:R4"])], [],
        [{"kind": "WINNER", "count": 78, "members": [["h1"]], "rowIds": ["P2:T1:R4"]}]))

    assert readable(result.sheet) == {"cells": ["16A/전체"], "quotas": [("WINNER", 78, ["16A/전체"])], "leaseTerms": []}


def test_a_target_only_named_by_a_member_is_added_after_the_listed_targets():
    selected = table_pages([["단지명", "주택형", "공급대상", "예비자"], ["Test", "26A", "청년", ""],
                            [None, None, "고령자", "4"]], page=2)

    result = model_supply(selected, answer(
        [housing_type("h1", "26A", ["P2:T1:R2"], "t1")], TARGETS,
        [{"kind": "RESERVE", "count": 4, "members": [["h1", "t2"]], "rowIds": ["P2:T1:R3"]}]))

    assert readable(result.sheet)["cells"] == ["26A/청년", "26A/고령자"]
    assert readable(result.sheet)["quotas"] == [("RESERVE", 4, ["26A/고령자"])]


def test_a_count_that_is_not_an_integer_is_not_added_and_leaves_the_cell_unresolved():
    selected = table_pages([["단지명", "주택형", "공급대상", "당첨자 수"], ["Test", "26A", "청년", "39"]], page=2)

    result = model_supply(selected, answer(
        [housing_type("h1", "26A", ["P2:T1:R2"], "t1")], TARGETS,
        [{"kind": "WINNER", "count": "19", "members": [["h1", "t1"]], "rowIds": ["P2:T1:R2"]},
         {"kind": "WINNER", "count": 39, "members": [["h1", "t1"]], "rowIds": ["P2:T1:R2"]}]))

    # 읽지 못한 수("19")를 더하지 않는다(58이 아니다). 그 칸의 모델 수는 미확인으로 남는다.
    assert 58 not in [quota["count"] for quota in result.sheet["quotas"]]
    assert "cells[0].winner" in result.unresolved() or "quotas[0].count" in result.unresolved()


def test_a_malformed_answer_drops_the_unreadable_parts_with_warnings_instead_of_failing_the_job():
    # 모델이 멤버를 중첩 목록·숫자로, kind를 목록으로, targets에 객체를 넣어도 작업 전체가 멈추지 않는다.
    selected = rows({"P4:T1:R4": {"complexName": "Test", "housingType": "24A", "target": "청년",
                                  "supplyHouseholdCount": "9"},
                     "P4:T1:R5": {"complexName": "Test", "housingType": "24B"},
                     "P5:T1:R3": {"rentalDeposit": "1,000"}})

    result = model_supply(selected, answer(
        [{"key": "h1", "complexName": "Test", "name": "24A", "rowIds": ["P4:T1:R4"], "targets": ["t1", {"key": "t2"}]},
         {"key": "h2", "complexName": "Test", "name": "24B", "rowIds": ["P4:T1:R5"], "targets": "t1"}], TARGETS,
        [{"kind": "WINNER", "count": 30, "members": [[["h1", "t1"]]], "rowIds": ["P4:T1:R4"]},
         {"kind": "WINNER", "count": 2, "members": 3, "rowIds": ["P4:T1:R4"]},
         {"kind": ["WINNER"], "count": 5, "members": [["h1", "t1"]], "rowIds": ["P4:T1:R4"]},
         {"kind": "WINNER", "count": 9, "members": [["h1", "t1"]], "rowIds": ["P4:T1:R4"]}],
        [{"members": [["h1", 1]], "rentalDeposit": 1000, "rowIds": ["P5:T1:R3"]}]))

    assert readable(result.sheet) == {"cells": ["24A/청년", "24B/전체"], "quotas": [("WINNER", 9, ["24A/청년"])],
                                      "leaseTerms": []}
    warnings = result.warnings
    assert sum("quotas[0]" in w for w in warnings) == 2 and sum("quotas[1]" in w for w in warnings) == 2 \
        and sum("quotas[2]" in w for w in warnings) == 1 and sum("leaseTerms[0]" in w for w in warnings) == 1
