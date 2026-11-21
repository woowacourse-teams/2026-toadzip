import json
from pathlib import Path

import pytest

from ai_worker import applicant_types
from ai_worker.supply_sheet import EVERYONE, TOTAL, Cite, Entry, Finding, Shared, SupplySheet, Unit


CASES = json.loads((Path(__file__).parents[1] / "contract" / "announcement-pdf-extraction" / "applicant-labels.json")
                   .read_text(encoding="utf-8"))["cases"]
NATIONAL = {"rentalType": "NATIONAL_RENTAL"}


def unit(name, count, area=29.39, complex_name="증평송산5", label=EVERYONE, reserve=None, on_entry=False, **values):
    """on_entry면 모집수를 단위가 아니라 대상 칸에 둔다(모델이 읽은 공급표 모양). values는 단위의 다른 값(건설호수 등)이다."""
    entry = {"supplyHouseholdCount": count if on_entry else None, "reserveCount": reserve,
             "rentalDeposit": 9091000, "monthlyRent": 182490}
    return Unit(complex_name, name, {TOTAL: None if on_entry else count, "exclusiveArea": area, **values},
                [Entry(label, entry)])


def emitted(sheet):
    result = sheet.emit(None)
    labels = {target["key"]: target["label"] for target in result["targets"]}
    names = {item["key"]: item["name"] for item in result["housingTypes"]}
    quotas = sorted((quota["kind"], quota["count"],
                     tuple(sorted((names[m["housingType"]], labels[m["target"]]) for m in quota["members"])))
                    for quota in result["quotas"])
    return [item["name"] for item in result["housingTypes"]], sorted(labels.values()), quotas, result


def test_national_rental_splits_applicant_marks_into_targets_of_one_housing_type():
    sheet = SupplySheet([unit("29", 50, reserve=50), unit("29 (주거약자)", 30, reserve=30), unit("37", 50, 37.28),
                         unit("46", 50, 46.21), unit("46 (주거약자)", 30, 46.21)])

    applicant_types.fold(sheet, NATIONAL)

    names, labels, quotas, result = emitted(sheet)
    assert names == ["29", "37", "46"]
    assert labels == ["일반", "전체", "주거약자"]
    # 신청자격이 주거약자 모집을 주거약자 경로로 따로 판정하게 하는 것이 목적이다.
    groups = {target["label"]: target["groups"] for target in result["targets"]}
    assert groups["일반"] == ["GENERAL"] and groups["주거약자"] == ["HOUSING_VULNERABLE"]
    assert ("WINNER", 50, (("29", "일반"),)) in quotas
    assert ("WINNER", 30, (("29", "주거약자"),)) in quotas
    assert ("RESERVE", 30, (("29", "주거약자"),)) in quotas
    assert ("WINNER", 30, (("46", "주거약자"),)) in quotas
    # 표기 있는 짝이 없는 37은 그대로 '전체' 하나다.
    assert ("WINNER", 50, (("37", "전체"),)) in quotas
    assert sheet.units[0].values[TOTAL] == 80


def test_counts_read_on_the_target_fold_the_same_way():
    sheet = SupplySheet([unit("29", 50, reserve=50, on_entry=True), unit("29 (주거약자)", 30, reserve=30, on_entry=True)])

    applicant_types.fold(sheet, NATIONAL)

    names, labels, quotas, _ = emitted(sheet)
    assert names == ["29"]
    assert labels == ["일반", "주거약자"]
    assert ("WINNER", 30, (("29", "주거약자"),)) in quotas and ("WINNER", 50, (("29", "일반"),)) in quotas


def test_other_rental_types_do_not_call_the_plain_row_general():
    sheet = SupplySheet([unit("55", 40, 55.0), unit("55(주거약자)", 10, 55.0)])

    applicant_types.fold(sheet, {"rentalType": "HAPPY_HOUSING"})
    assert [entry.label for entry in sheet.units[0].entries] == ["주거약자 외", "주거약자"]

    sheet = SupplySheet([unit("55", 40, 55.0), unit("55(주거약자)", 10, 55.0), unit("55(고령자)", 5, 55.0)])
    applicant_types.fold(sheet, None)
    assert [entry.label for entry in sheet.units[0].entries] == ["그 밖의 대상", "주거약자", "고령자"]


def test_unit_count_evidence_and_findings_move_to_the_target_they_now_count():
    plain, marked = unit("29", 50), unit("29 (주거약자)", 30)
    row_cite = Cite(marked, "", 4, "29 (주거약자) 29.39 30 주거약자용")
    sheet = SupplySheet([plain, marked], evidence=[Cite(marked, TOTAL, 4, "30"), row_cite, Cite(marked, "exclusiveArea", 4, "29.39")],
                        findings=[Finding(marked, TOTAL, "모집수 확인 필요")])

    applicant_types.fold(sheet, NATIONAL)

    entry = marked.entries[0]
    assert Cite(entry, "supplyHouseholdCount", 4, "30") in sheet.evidence
    assert Cite(entry, "", 4, row_cite.quote) in sheet.evidence
    assert Cite(plain, "", 4, row_cite.quote) in sheet.evidence
    assert Cite(plain, "exclusiveArea", 4, "29.39") in sheet.evidence
    assert all(cite.owner is not marked for cite in sheet.evidence)
    assert sheet.findings == [Finding(entry, "supplyHouseholdCount", "모집수 확인 필요")]
    paths = {item["path"] for item in emitted(sheet)[3]["evidence"]}
    assert any(path.startswith("quotas[") for path in paths)


def test_leaves_shapes_it_cannot_fold_safely():
    cases = [
        [unit("55C(주거약자)", 10, 55.0), unit("37", 50, 37.0)],  # 표기 없는 짝이 없다
        [unit("29", 50), unit("29 (주거약자)", 30, 29.5)],  # 면적이 다르다
        [unit("29", 50), unit("29 (주거약자)", 30, complex_name="다른단지")],  # 단지가 다르다
        [unit("29", 50), unit("29 (주거약자)", 30, label="신혼부부")],  # 대상 열이 있는 표다
        [unit("29", 50), unit("29 (주거약자)", None)],  # 모집수를 읽지 못했다
        [unit("29", 50, commonArea=15.97), unit("29 (주거약자)", 30, commonArea=16.5)],  # 공용면적이 다르다
        [unit("29", 50, constructedHouseholdCount=167), unit("29 (주거약자)", 30)],  # 건설호수를 한 행만 적었다
    ]
    for units in cases:
        sheet = SupplySheet(units)
        applicant_types.fold(sheet, NATIONAL)
        assert [item.name for item in sheet.units] == [item.name for item in units]

    plain, marked = unit("29", 50), unit("29 (주거약자)", 30)
    sheet = SupplySheet([plain, marked], shared=[Shared("WINNER", 80, [plain, marked], 4, "80")])
    applicant_types.fold(sheet, NATIONAL)
    assert len(sheet.units) == 2


def test_built_counts_add_up_and_blank_values_are_filled_from_the_marked_row():
    sheet = SupplySheet([unit("29", 50, constructedHouseholdCount=167, commonArea=None),
                         unit("29 (주거약자)", 30, constructedHouseholdCount=15, commonArea=15.97)])

    applicant_types.fold(sheet, NATIONAL)

    assert sheet.units[0].values["constructedHouseholdCount"] == 182
    assert sheet.units[0].values["commonArea"] == 15.97


@pytest.mark.parametrize("case", CASES, ids=lambda case: f"{case['rentalType']}:{'/'.join(case['typeNames'])}")
def test_labels_follow_the_contract_shared_with_the_server(case):
    sheet = SupplySheet([unit(name, 10, 55.0) for name in case["typeNames"]])

    applicant_types.fold(sheet, {"rentalType": case["rentalType"]})

    assert [entry.label for entry in sheet.units[0].entries] == case["labels"]


def test_reads_the_rental_type_from_the_job_input_contract():
    example = json.loads((Path(__file__).parents[1] / "contract" / "announcement-pdf-extraction" / "input.example.json")
                         .read_text(encoding="utf-8"))
    assert example["rentalType"] == "HAPPY_HOUSING"  # 서버가 쓰는 키 이름 그대로 읽는다
    sheet = SupplySheet([unit("55", 40, 55.0), unit("55(주거약자)", 10, 55.0)])

    applicant_types.fold(sheet, {**example, "rentalType": "NATIONAL_RENTAL"})

    assert sheet.units[0].entries[0].label == "일반"
