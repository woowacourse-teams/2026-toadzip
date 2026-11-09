from ai_worker.source_frame import SourceFrame, SourceSupplyRow
from conftest import contract_example


def gh_row(identifier, complex_label, type_name, exclusive_area=""):
    return {"source": "GH", "order": 0, "rowIdentifier": identifier, "complexLabel": complex_label, "typeName": type_name,
            "exclusiveArea": exclusive_area, "supplyArea": "", "suppliedUnitCount": "", "totalUnitCount": ""}


def supply_row(complex_name, housing_type, exclusive_area=None, source_row=None, subtypes=()):
    return {"complexReference": {"sourceComplexName": complex_name}, "sourceHousingTypeName": housing_type,
            "exclusiveArea": exclusive_area, "sourceRow": source_row,
            "subtypes": [{"name": name, "exclusiveArea": area} for name, area in subtypes], "targets": []}


def link(frame_rows, supply_rows):
    tree = {"data": {"supplyRows": supply_rows}}
    warnings = SourceFrame.read({"sourceSupplyRows": frame_rows}).link(tree)
    return [row["sourceRow"] for row in supply_rows], warnings


def test_input_example_gives_only_lh_rows_to_the_model_and_matches_gh_rows_by_code():
    # 예시는 두 원천의 행 모양을 함께 보인다. 실제 한 공고는 한 기관 원천만 가진다.
    frame = SourceFrame.read(contract_example("input.example.json"))

    assert frame.lh_rows == [
        SourceSupplyRow("LH", "LH:2015122300018207:SUPPLY:0", "인천부평(도시재생뉴딜) 행복주택", "16A(대학생/청년)", "16.37",
                        "27.6384", "78"),
    ]
    rows = [supply_row("다산 센트럴파크6단지", "36A", 36.8)]
    assert frame.link({"data": {"supplyRows": rows}}) == []
    assert rows[0]["sourceRow"] == "GH:801:다산 센트럴파크6단지:36A"


def test_gh_rows_are_not_given_to_the_model():
    frame = SourceFrame.read({"sourceSupplyRows": [gh_row("GH:801:다산:36A", "다산", "36A 청년")]})

    assert frame.lh_rows == []


def test_lh_rows_do_not_fill_source_row():
    # LH 행은 공급행에 대응하지 않는 참고 자료다. 화면이 전용면적으로 따로 맞춘다.
    frame_rows = [{"source": "LH", "rowIdentifier": "LH:P:SUPPLY:1", "complexLabel": "A", "typeName": "51",
                   "exclusiveArea": "51.98", "supplyArea": "", "suppliedUnitCount": "200"}]

    assert link(frame_rows, [supply_row("A", "51", 51.98)]) == ([None], [])


def test_gh_frame_row_with_same_district_and_housing_type_token_is_linked():
    # GH 틀 이름은 "36A 청년"처럼 공급대상이 붙고, PDF 지구명은 띄어쓰기가 다를 수 있다.
    frame_rows = [gh_row("GH:801:다산 센트럴파크6단지:33A", "다산 센트럴파크6단지", "33A"),
                  gh_row("GH:801:다산 센트럴파크6단지:36A", "다산 센트럴파크6단지", "36A 청년")]

    assert link(frame_rows, [supply_row("다산 센트럴파크 6단지", "36a"), supply_row("다산센트럴파크6단지", "33A")]) == (
        ["GH:801:다산 센트럴파크6단지:36A", "GH:801:다산 센트럴파크6단지:33A"], [])


def test_gh_rows_of_same_type_are_told_apart_by_exclusive_area():
    frame_rows = [gh_row("GH:1:광교:36A", "광교", "36A 청년", "36.7"), gh_row("GH:1:광교:36A#2", "광교", "36A 신혼부부", "36.83")]

    assert link(frame_rows, [supply_row("광교", "36A", 36.83)]) == (
        ["GH:1:광교:36A#2"], ["PDF에서 찾지 못한 GH 공급행 틀 1개: 광교 36A 청년"])


def test_gh_rows_that_cannot_be_told_apart_are_left_for_the_admin():
    frame_rows = [gh_row("GH:1:광교:36A", "광교", "36A 청년", "36.7"), gh_row("GH:1:광교:36A#2", "광교", "36A 신혼부부", "36.7")]

    rows, warnings = link(frame_rows, [supply_row("광교", "36A", 36.7)])

    assert rows == [None]
    assert warnings[0] == "supplyRows[0].sourceRow: GH 공급행 틀에 같은 지구·주택형 행이 2개라 하나로 정하지 못함(광교 36A)"


def test_gh_rows_whose_area_does_not_settle_the_type_are_left_for_the_admin():
    frame_rows = [gh_row("GH:1:광교:36A", "광교", "36A 청년", "36.7"), gh_row("GH:1:광교:36A#2", "광교", "36A 신혼부부", "36.83")]

    rows, warnings = link(frame_rows, [supply_row("광교", "36A", 40.0)])

    assert rows == [None]
    assert warnings[0] == "supplyRows[0].sourceRow: GH 공급행 틀에 같은 지구·주택형 행이 2개라 하나로 정하지 못함(광교 36A)"


def test_pdf_rows_and_frame_rows_left_unmatched_are_summarized_in_one_line_each():
    # 맞지 않은 행마다 경고를 남기면 실제 공고에서 수십 줄이 되어 다른 경고가 묻힌다.
    frame_rows = [gh_row("GH:1:광교:36A", "광교", "36A 청년"), gh_row("GH:1:영통:22B", "영통", "22B")]

    assert link(frame_rows, [supply_row("광교", "38A"), supply_row("동탄", "36A")]) == ([None, None], [
        "GH 공급행 틀과 맞추지 못한 PDF 공급행 2개: 광교 38A, 동탄 36A",
        "PDF에서 찾지 못한 GH 공급행 틀 2개: 광교 36A 청년, 영통 22B",
    ])


def test_gh_district_written_differently_in_the_pdf_is_matched_when_only_one_district_contains_it():
    # 실제 GH 원천 지구명은 "안양관양 경기행복주택", 공고문 PDF 표는 "안양 관양"처럼 쓴다.
    frame_rows = [gh_row("GH:839:안양관양 경기행복주택:36A", "안양관양 경기행복주택", "36A 청년", "36.4"),
                  gh_row("GH:839:남양주 다산역A2 경기행복주택:24A,B,C", "남양주 다산역A2 경기행복주택", "24A,B,C 청년"),
                  gh_row("GH:839:수원광교 경기행복주택:36A", "수원광교 경기행복주택", "36A 청년"),
                  gh_row("GH:839:광교원천 경기행복주택:36A", "광교원천 경기행복주택", "36A 청년")]

    rows, warnings = link(frame_rows, [supply_row("안양 관양", "36A", 36.4), supply_row("다산역 A2", "24ABC"),
                                       supply_row("광교", "36A")])

    # "광교"는 두 지구에 들어 있어 정하지 않는다.
    assert rows == ["GH:839:안양관양 경기행복주택:36A", "GH:839:남양주 다산역A2 경기행복주택:24A,B,C", None]
    assert warnings == [
        "GH 공급행 틀과 맞추지 못한 PDF 공급행 1개: 광교 36A",
        "PDF에서 찾지 못한 GH 공급행 틀 2개: 수원광교 경기행복주택 36A 청년, 광교원천 경기행복주택 36A 청년",
    ]


def test_pdf_name_longer_than_the_frame_district_is_not_taken_as_that_district():
    # PDF 이름이 틀 지구명을 품는 방향("수원 광교 원천" ⊃ "광교")은 다른 지구일 수 있어 맞추지 않는다.
    # 틀에 단지가 하나뿐이면 이름이 달라도 그 단지라(사용자 결정), 단지가 여럿인 틀로 본다.
    frame_rows = [gh_row("GH:1:광교 경기행복주택:36A", "광교 경기행복주택", "36A 청년"),
                  gh_row("GH:1:안양관양 경기행복주택:36A", "안양관양 경기행복주택", "36A 청년")]

    rows, _ = link(frame_rows, [supply_row("광교원천", "36A")])

    assert rows == [None]


def test_gh_frame_row_matched_by_several_pdf_rows_is_left_for_the_admin():
    # 화면은 GH 식별자로 그 틀 행을 고친다. 두 PDF 행이 한 틀 행을 고칠 수는 없어 둘 다 비운다.
    frame_rows = [gh_row("GH:1:광교:36A", "광교", "36A 청년")]

    assert link(frame_rows, [supply_row("광교", "36A", 36.7), supply_row("광교", "36A 청년", 36.9)]) == ([None, None], [
        "supplyRows[0], supplyRows[1]: 같은 GH 공급행 틀(광교 36A 청년)에 맞아 하나로 정하지 못함",
    ])


DASAN = "다산진건B1블록 장기전세주택"


def test_single_complex_frame_is_matched_by_housing_type_even_when_the_district_name_differs():
    # 작업 121: 틀 지구명(다산진건B1블록 장기전세주택)과 PDF 단지명(다산 진건 데시앙)이 다르다. 틀에 단지가 하나뿐이면 그 단지다.
    frame = [gh_row(f"GH:808:{DASAN}:{name}", DASAN, name, area)
             for name, area in (("72A", "72.9858"), ("84A", "84.9876"), ("84B", "84.9585"))]
    rows, warnings = link(frame, [supply_row("다산 진건 데시앙", name, area)
                                  for name, area in (("72A", 72.98), ("84A", 84.98), ("84B", 84.95))])

    assert rows == [f"GH:808:{DASAN}:{name}" for name in ("72A", "84A", "84B")]
    assert warnings == []


def test_single_complex_frame_falls_back_to_one_row_of_the_same_area_and_not_to_several():
    frame = [gh_row(f"GH:808:{DASAN}:{name}", DASAN, name, area)
             for name, area in (("72A", "72.9858"), ("84C1", "84.9825"), ("84C2", "84.9825"))]

    # 주택형이 같은 틀 행이 없으면 면적이 같은 행 하나와 맞춘다.
    assert link(frame, [supply_row("다산 진건 데시앙", "72", 72.98)])[0] == [f"GH:808:{DASAN}:72A"]
    # 면적이 같은 틀 행이 여럿이면 잇지 않는다.
    assert link(frame, [supply_row("다산 진건 데시앙", "84", 84.98)])[0] == [None]


def test_frame_with_several_complexes_still_needs_a_matching_district_name():
    frame = [gh_row("GH:900:갑지구:59A", "갑지구", "59A", "59.98"), gh_row("GH:900:을지구:59A", "을지구", "59A", "59.98")]

    rows, warnings = link(frame, [supply_row("병 단지", "59A", 59.98)])

    assert rows == [None]
    assert any("맞추지 못한 PDF 공급행" in warning for warning in warnings)


def test_housing_type_row_grouping_several_house_types_is_not_linked_by_area():
    # 84(84A·84C1·84C2 / 84B·84D)는 틀 행 하나가 아니라 여러 주택타입이다. 면적이 맞는 84A에 이으면 84B 수가 두 번 들어간다.
    frame = [gh_row(f"GH:808:{DASAN}:{name}", DASAN, name, area)
             for name, area in (("72A", "72.9858"), ("84A", "84.9876"), ("84B", "84.9585"))]
    rows, _ = link(frame, [supply_row("다산 진건 데시앙", "72", 72.98, subtypes=[("72A", 72.98)]),
                           supply_row("다산 진건 데시앙", "84", 84.98, subtypes=[("84A·84C1·84C2", 84.98), ("84B·84D", 84.95)])])

    assert rows == [f"GH:808:{DASAN}:72A", None]


def test_area_fallback_only_uses_frame_rows_left_after_matching_by_housing_type():
    # 틀에 84C1만 있다. 84C1은 주택형으로 맞고, 84C2는 남은 틀 행이 없어 잇지 않는다.
    frame = [gh_row(f"GH:808:{DASAN}:84C1", DASAN, "84C1", "84.9825")]
    rows, warnings = link(frame, [supply_row("다산 진건 데시앙", "84C1", 84.98), supply_row("다산 진건 데시앙", "84C2", 84.98)])

    assert rows == [f"GH:808:{DASAN}:84C1", None]
    assert warnings == ["GH 공급행 틀과 맞추지 못한 PDF 공급행 1개: 다산 진건 데시앙 84C2"]


def test_area_fallback_with_several_frame_rows_of_that_area_is_warned_as_such():
    frame = [gh_row(f"GH:808:{DASAN}:{name}", DASAN, name, "84.9825") for name in ("84C1", "84C2")]
    rows, warnings = link(frame, [supply_row("다산 진건 데시앙", "84C", 84.98)])

    assert rows == [None]
    assert any("전용면적이 같은 틀 행이 2개" in warning for warning in warnings)


def test_single_complex_frame_still_needs_names_when_the_pdf_has_several_complexes():
    frame = [gh_row(f"GH:808:{DASAN}:72A", DASAN, "72A", "72.9858")]
    rows, _ = link(frame, [supply_row("다산 진건 데시앙", "72A", 72.98), supply_row("다른 단지", "59A", 59.98)])

    assert rows == [None, None]


def test_one_subtype_entry_naming_several_house_types_is_not_linked_by_area():
    # 세부 타입 하나가 같은 면적의 주택타입 여럿(84A·84C1·84C2)이면 묶은 주택형이라 84A 틀 행 하나에 잇지 않는다.
    frame = [gh_row(f"GH:808:{DASAN}:{name}", DASAN, name, area)
             for name, area in (("72A", "72.9858"), ("84A", "84.9876"), ("84B", "84.9585"))]
    rows, _ = link(frame, [supply_row("다산 진건 데시앙", "72A", 72.98),
                           supply_row("다산 진건 데시앙", "84", 84.98, subtypes=[("84A·84C1·84C2", 84.98)]),
                           supply_row("다산 진건 데시앙", "84B", 84.95)])

    assert rows == [f"GH:808:{DASAN}:72A", None, f"GH:808:{DASAN}:84B"]
