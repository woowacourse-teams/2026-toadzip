import pytest

from ai_worker import pages, source_check
from ai_worker.supply_sheet import Cite, Entry, Finding, Unit


def source(text, values, headings=None):
    """원문 표 행(행 ID → 글·칸)만 담은 입력."""
    return pages.PromptPages(
        rental_rows=0, supply_text="", supply_pages=[1, 4], supply_row_ids=frozenset(text), supply_row_text=text,
        notice_text="", notice_pages=[], complex_candidates=[], table_structure_fallback=False,
        ambiguous_supply_tables=False, supply_row_values=values, supply_row_headings=headings or {})


ROW = {"P1:T1:R2": "Test\t26A\t청년\t20"}
ROW_VALUES = {"P1:T1:R2": {"complexName": "Test", "housingType": "26A", "target": "청년", "reserveCount": "20"}}


def check(owner, quote, selected, row_ids=("P1:T1:R2",), page=1, headings=None, confirmed=None):
    cite = Cite(owner, "", page, quote) if quote is not None else None
    return source_check.check_source(owner, set(row_ids), cite, selected, headings, confirmed or {})


@pytest.mark.parametrize("quote, reserve", [("26A 청년", 99), ("26A 청년 20", 26)])
def test_count_not_in_the_same_meaning_source_cell_is_cleared_and_unresolved(quote, reserve):
    # 99는 원문 행에 없고, 26은 원문 행의 다른 칸(주택형 26A)에만 있다. 예비자 칸은 20이다.
    entry = Entry("청년", {"reserveCount": reserve})

    findings, failure = check(entry, quote, source(ROW, ROW_VALUES))

    assert entry.values["reserveCount"] is None
    assert findings == [Finding(entry, "reserveCount", "추출한 수치가 같은 의미의 원문 표 열에서 확인되지 않음")]
    # 근거는 확인됐으므로 경고할 사유는 없고, 값을 비운 사유는 미확인에만 남는다.
    assert failure is None


def test_names_must_match_their_meaningful_source_cells():
    unit = Unit("Fake", "26B", {})
    entry = Entry("고령자", {"reserveCount": 20})
    selected = source(ROW, ROW_VALUES)

    unit_findings, _ = check(unit, "Test 26A", selected)
    entry_findings, _ = check(entry, "청년 20", selected)

    assert (unit.complex_name, unit.name, entry.label) == ("", None, "")
    assert [(finding.owner, finding.field) for finding in unit_findings + entry_findings] == [
        (unit, "name"), (unit, "complexName"), (entry, "label")]
    # 예비자 수 20은 원문 칸과 같아 남는다.
    assert entry.values["reserveCount"] == 20


def test_target_group_notation_matches_without_its_corner_brackets():
    entry = Entry("가군", {"reserveCount": 20})
    selected = source({"P1:T1:R2": "Test\t26A\t「가」군\t20"},
                      {"P1:T1:R2": {"complexName": "Test", "housingType": "26A", "target": "「가」군", "reserveCount": "20"}})

    findings, _ = check(entry, "「가」군 20", selected)

    assert entry.label == "가군"
    assert findings == []


def test_confirmed_value_is_checked_instead_of_the_source_cells():
    # 주택타입 행 수량의 합(41 + 3)은 원문 칸 하나에 없다. 합을 정하지 못한 필드(None)는 원문 칸과 같아도 확인하지 못한다.
    unit = Unit("Test", "26A", {"totalSupplyHouseholdCount": 44})
    entry = Entry("청년", {"supplyHouseholdCount": 44, "reserveCount": 20})
    selected = source(ROW, ROW_VALUES)

    check(unit, "Test 26A", selected, confirmed={"totalSupplyHouseholdCount": 44})
    findings, _ = check(entry, "청년 20", selected, confirmed={"supplyHouseholdCount": 44, "reserveCount": None})

    assert unit.values["totalSupplyHouseholdCount"] == 44
    assert entry.values == {"supplyHouseholdCount": 44, "reserveCount": None}
    assert [finding.field for finding in findings] == ["reserveCount"]


def test_complex_name_without_a_complex_column_must_be_the_nearest_heading_above_its_table():
    # 광주선운2: 블록 제목(■ … A-1블록) 아래 두 표, 그 아래 A-3블록 제목과 두 표. 표에는 단지명 열이 없다.
    # A-3블록 표의 행에 위쪽 A-1블록 이름을 붙인 값은 받지 않는다.
    above_a1 = "2. 임대대상 및 임대조건\n■ 광주선운2 A-1블록 (총 336세대)\n[단지전체현황]\n55A 217\n[금회모집 임대조건]"
    above_a3 = f"{above_a1}\n55A-1 신혼부부· 한부모가족 20\n■ 광주선운2 A-3블록 (총 147세대)\n[금회모집 임대조건]"
    selected = source({"P4:T2:R5": "55B-1\t\t-\t20", "P4:T4:R4": "55B-1\t신혼부부· 한부모가족\t-\t20"},
                      {"P4:T2:R5": {"housingType": "55B-1", "target": "신혼부부·한부모가족", "reserveCount": "20"},
                       "P4:T4:R4": {"housingType": "55B-1", "target": "신혼부부·한부모가족", "reserveCount": "20"}},
                      {"P4:T2:R5": above_a1, "P4:T4:R4": above_a3})
    headings = source_check.Headings(selected.supply_row_headings, ("광주선운2 A-1블록", "광주선운2 A-3블록"))
    units = [Unit(name, "55B-1", {}) for name in ("광주선운2 A-1블록", "광주선운2 A-3블록", "광주선운2 A-1블록")]

    findings = [finding for unit, row_id in zip(units, ["P4:T2:R5", "P4:T4:R4", "P4:T4:R4"])
                for finding in check(unit, "55B-1", selected, [row_id], 4, headings)[0]]

    assert [unit.complex_name for unit in units] == ["광주선운2 A-1블록", "광주선운2 A-3블록", ""]
    assert findings == [Finding(units[2], "complexName", "추출한 값이 같은 의미의 원문 표 열에서 확인되지 않음")]


def test_heading_is_not_used_when_the_row_has_a_complex_cell():
    unit = Unit("광주선운2 A-1블록", "55B-1", {})
    selected = source({"P4:T2:R5": "광주선운2 A-3블록\t55B-1"},
                      {"P4:T2:R5": {"complexName": "광주선운2 A-3블록", "housingType": "55B-1"}},
                      {"P4:T2:R5": "■ 광주선운2 A-3블록\n55A 20\n■ 광주선운2 A-1블록"})

    check(unit, "55B-1", selected, ["P4:T2:R5"], 4,
          source_check.Headings(selected.supply_row_headings, ("광주선운2 A-1블록", "광주선운2 A-3블록")))

    assert unit.complex_name == ""


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


@pytest.mark.parametrize("row_ids, quote, page, reason", [
    ((), "26A 청년", 1, "원문 표 행 ID를 확인하지 못함"),
    (("P9:T1:R1",), "26A 청년", 9, "응답의 원문 표 행 ID가 입력 표와 일치하지 않음"),
    (("P1:T1:R2",), None, 1, "근거 인용을 지정한 원문 표 행에서 확인하지 못함"),
    (("P1:T1:R2",), "26A 고령자", 1, "근거 인용을 지정한 원문 표 행에서 확인하지 못함"),
    (("P1:T1:R2",), "26A 청년", 4, "근거 쪽과 원문 표 행 ID의 쪽이 일치하지 않음"),
])
def test_unsupported_evidence_clears_every_value_and_marks_each_one_unresolved(row_ids, quote, page, reason):
    entry = Entry("청년", {"reserveCount": 20, "rentalDeposit": 46920000, "monthlyRent": None})

    findings, failure = check(entry, quote, source(ROW, ROW_VALUES), row_ids, page)

    # 경고할 사유는 하나이고, 비운 값마다의 사유는 미확인에만 남는다.
    assert failure == reason
    assert (entry.label, entry.values["reserveCount"], entry.values["rentalDeposit"]) == ("", None, None)
    assert findings == [Finding(entry, "", reason), Finding(entry, "label", f"{reason}; 문자열 원문을 확인하지 못함"),
                        Finding(entry, "reserveCount", f"{reason}; 수치 원문을 확인하지 못함"),
                        Finding(entry, "rentalDeposit", f"{reason}; 수치 원문을 확인하지 못함")]
