"""공급표의 수량·임대조건·대상을 어떻게 나누고 묶는지(서버 투영과 같은 규칙). 공급 단계 모델의 답과 쪽 표에서 확인한다."""

from ai_worker import checks, merge, pages
from ai_worker.eligibility import Extracted
from ai_worker.source_frame import SourceFrame, SourceSupplyRow
from ai_worker.supply_sheet import Entry, Finding, SupplySheet, Unit
from conftest import model_stage, model_supply, readable
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
    assert result.unresolved() == {"housingTypes[1].exclusiveArea"}


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


def test_types_sharing_one_count_stay_one_unit_through_merge_review_and_gh_linking():
    # 경계: 모집 칸을 함께 쓰는 49A·49B·49C는 조립부터 내보낼 때까지 모집 단위 하나다. 합친 단지명, LH 검산(주택형마다 면적),
    # GH 틀(49형 한 줄) 잇기가 그 단위 위에서 일어나고, 내보낼 때 주택형마다 나뉜 자리에 같은 값이 실린다.
    table = [[*row, header] for row, header in zip(SHARED_COUNT_TABLE, ("임대보증금(원)", None, "1,000", None, None))]
    selected = table_pages(table, page=2)
    supply = model_stage(selected, answer(
        [housing_type(f"h{number}", label, [f"P2:T1:R{number}"], complexName="탑석 센트럴 자이")
         for number, label in ((3, "49A"), (4, "49B"), (5, "49C"))], [],
        leases=[{"members": [["h3"], ["h4"], ["h5"]], "rentalDeposit": 1000, "monthlyRent": 50,
                 "rowIds": ["P2:T1:R3"]}]))
    notice = {"data": {"announcement": {"name": "공고", "postedDate": "2026-10-01"}, "receptionPlaces": [],
                       "schedules": [], "unresolvedFields": [], "complexes": [{"sourceComplexName": "탑석센트럴자이"}]},
              "notApplicable": [], "evidence": []}
    tree, _ = merge.merge(supply.tree, supply.sheet, notice, Extracted(
        {"common": {}, "exclusions": [], "targets": [{"target": "전체", "supplyTarget": "전체"}]}))
    lh_rows = [SourceSupplyRow("LH", None, "탑석센트럴자이", "49A", "49.9285", "", "30"),
               SourceSupplyRow("LH", None, "탑석센트럴자이", "49B", "49.9091", "", "30")]
    frame = SourceFrame.read({"sourceSupplyRows": [
        {"source": "GH", "rowIdentifier": "GH:845:탑석센트럴자이:49형", "complexLabel": "탑석센트럴자이", "typeName": "49형",
         "exclusiveArea": "", "supplyArea": "", "suppliedUnitCount": ""}]})

    page_text = "\n".join(selected.supply_row_text.values())
    warnings = checks.review(tree, supply.sheet, [1, 2], ["", page_text], lh_rows, 0)
    warnings += frame.link(supply.sheet)
    sheet = supply.sheet.emit(tree["data"]["eligibility"])

    assert warnings == [
        "탑석 센트럴 자이 49 모집수: 같은 전용면적(49.93, 49.91, 49.94) LH API 공급행 2줄의 모집수 합계 60와 다름(70)"]
    assert [(item["complexName"], item["name"], item["sourceRow"]) for item in sheet["housingTypes"]] == [
        ("탑석 센트럴 자이", name, "GH:845:탑석센트럴자이:49형") for name in ("49A", "49B", "49C")]
    every = ["49A/전체", "49B/전체", "49C/전체"]
    assert readable(sheet) == {"cells": every, "quotas": [("WINNER", 70, every), ("RESERVE", 56, every)],
                               "leaseTerms": [(1000, None, every)]}
    assert tree["data"]["eligibility"]["targets"][0]["supplyTargetKeys"] == ["t0"]
    # 원문에 없는 월임대료는 세 주택형이 함께 쓰는 임대조건 하나를 가리킨다. 근거는 주택형·칸마다 하나, 수량·임대조건은 하나씩이다.
    assert sheet["unresolvedFields"] == [
        {"path": "leaseTerms[0].monthlyRent", "reason": "추출한 수치가 같은 의미의 원문 표 열에서 확인되지 않음"}]
    assert [(item["path"], item["page"]) for item in sheet["evidence"]] == [
        (path, 2) for path in ("housingTypes[0]", "housingTypes[1]", "housingTypes[2]", "cells[0]", "cells[1]", "cells[2]",
                               "quotas[0]", "quotas[1]", "leaseTerms[0]")]


def test_same_target_twice_in_one_unit_is_one_cell_with_summed_counts_and_the_first_amounts():
    # 같은 행에 같은 공급대상이 두 번 나오면(같은 표기·신청 조건) 칸은 하나다. 수량은 더하고 임대조건은 그 칸을 만든 첫 대상의
    # 금액 하나다(서버 투영과 같다). 둘째 대상의 금액 미확인은 임대조건이 아니라 그 칸 자리를 가리킨다.
    first = Entry("청년", {"supplyHouseholdCount": 3, "reserveCount": 1, "rentalDeposit": 100})
    second = Entry("청년", {"supplyHouseholdCount": 2, "rentalDeposit": 200})
    other = Entry("청년", {"supplyHouseholdCount": 4, "applicationCondition": "대학생 제외"})
    unit = Unit("Test", "26A", {"totalSupplyHouseholdCount": 9}, [first, second, other])
    sheet = SupplySheet([unit], findings=[Finding(second, "rentalDeposit", "확인 못함"), Finding(first, "reserveCount", "a")])

    result = sheet.emit(None)

    assert [(target["label"], target["applicationCondition"]) for target in result["targets"]] == [
        ("청년", None), ("청년", "대학생 제외")]
    assert readable(result) == {
        "cells": ["26A/청년", "26A/청년"],
        "quotas": [("WINNER", 5, ["26A/청년"]), ("RESERVE", 1, ["26A/청년"]), ("WINNER", 4, ["26A/청년"])],
        "leaseTerms": [(100, None, ["26A/청년"])],
    }
    assert result["unresolvedFields"] == [{"path": "cells[0].rentalDeposit", "reason": "확인 못함"},
                                          {"path": "quotas[1].count", "reason": "a"}]
