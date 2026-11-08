from ai_worker import pages, supply_assembly, supply_grouping
from ai_worker.tables import SupplyTable

QUANTITY_HEADER = [
    ["단지명", "청약구분", "공급대상", "모집호수 예비자", "주거전용", "주거공용"],
]
RENT_HEADER = [
    ["단지명", "청약구분", "공급대상", None, "임대보증금(천원)", "월임대료(원)"],
]


def table(page, cells, header=QUANTITY_HEADER, ambiguous=False, inherit=None):
    """표 인식기처럼 병합 칸은 None, 빈칸은 ''로 준다. inherit가 있으면 머리글 없는 이어짐 표다."""
    rows = cells if inherit else [*header, *cells]
    return SupplyTable.of(page, 1, rows, inherit.columns if inherit else None, ambiguous)


def row_values(tables):
    return {row.id: row.values for item in tables for row in item.rows}


def assemble(tables, tableless_pages=()):
    values = row_values(tables)
    grouped = supply_grouping.group(tables, values, list(tableless_pages))
    assert grouped is not None
    selected = pages.PromptPages(
        rental_rows=0, supply_text="", supply_pages=sorted({item.page for item in tables}),
        supply_row_ids=frozenset(values), supply_row_text={key: "row" for key in values},
        notice_text="", notice_pages=[], complex_candidates=[], table_structure_fallback=False,
        ambiguous_supply_tables=False, supply_row_values=grouped.row_values,
        supply_row_subtypes={row.id: row.subtypes for item in tables for row in item.rows if row.subtypes},
    )
    tree, _ = supply_assembly.assemble(grouped.compact, selected)
    return grouped, tree


def targets(tree):
    return [(row["complexReference"]["sourceComplexName"], row["sourceHousingTypeName"], target["target"])
            for row in tree["data"]["supplyRows"] for target in row["targets"]]


def test_merged_cells_become_shared_counts_area_lines_and_inherited_names_across_pages():
    page4 = table(4, [
        ["Alpha", "26B", "청년", "39", "26.4422", "15.2435"],
        [None, None, None, None, "26.5761", "15.3207"],
        [None, "36AB", "신혼부부", "136", "36.1396", ""],
        [None, "36AB1", "청년", None, "36.2643", "20.9058"],
    ])
    page5 = table(5, [
        ["", "44A", "고령자", "3", "44.1269", ""],
        [None, None, None, None, None, "25.4384"],
    ], inherit=page4)

    grouped, tree = assemble([page4, page5])

    assert targets(tree) == [("Alpha", "26B", "청년"), ("Alpha", "36AB", "신혼부부"),
                             ("Alpha", "36AB1", "청년"), ("Alpha", "44A", "고령자")]
    rows = tree["data"]["supplyRows"]
    # 아래 면적 줄은 같은 공급행의 다른 면적이라 공급행을 늘리지 않고 첫 면적을 대표로 둔다.
    assert rows[0]["exclusiveArea"] == 26.4422 and rows[0]["targets"][0]["reserveCount"] == 39
    # 두 대상에 걸친 모집수 칸은 복제하지 않고 공통 수량으로 남긴다.
    assert rows[1]["targets"][0]["reserveCount"] is None and rows[2]["targets"][0]["reserveCount"] is None
    assert [(item["paths"], item["count"]) for item in tree["sharedCounts"]] == [(
        ["supplyRows[1].targets[0].reserveCount", "supplyRows[2].targets[0].reserveCount"], 136)]
    # 다음 쪽 첫 행의 빈 단지명은 앞 쪽 단지를 이어받고, 격자로 쪼개진 칸의 공용면적을 채운다.
    assert rows[3]["commonArea"] == 25.4384
    assert "P4:T1:R2" in next(item["rowIds"] for item in tree["sourceRows"] if item["path"] == "supplyRows[3]")
    assert tree["data"]["unresolvedFields"] == []
    assert grouped.warnings == []


def test_income_split_rent_rows_are_separate_targets_sharing_the_supply_count():
    quantity = table(4, [["Alpha", "24ABC", "청년", "20", "24.97", "15.37"]])
    rent = table(8, [
        ["Alpha", "24ABC", "청년", "소득 없음", "46,920", "178,670"],
        [None, None, None, None, None, None],
        [None, None, None, "소득 있음", "49,680", "189,910"],
    ], header=RENT_HEADER)

    _, tree = assemble([quantity, rent])

    target_values = [(target["target"], target["rentalDeposit"], target["monthlyRent"], target["reserveCount"])
                     for target in tree["data"]["supplyRows"][0]["targets"]]
    assert target_values == [("청년(소득 없음)", 46920000, 178670, None), ("청년(소득 있음)", 49680000, 189910, None)]
    assert [(item["paths"], item["count"]) for item in tree["sharedCounts"]] == [(
        ["supplyRows[0].targets[0].reserveCount", "supplyRows[0].targets[1].reserveCount"], 20)]
    assert tree["data"]["unresolvedFields"] == []


def test_one_rent_row_listing_several_types_and_targets_applies_to_each():
    quantity = table(4, [
        ["Beta", "17B", "고령자", "3", "17.1", "9.1"],
        [None, "17C", "고령자(주거약자)", "1", "17.2", "9.2"],
    ])
    rent = table(8, [["Beta", "17B 17C", "고령자, 고령자 ( 주거약자)", None, "31,160", "123,860"]], header=RENT_HEADER)

    _, tree = assemble([quantity, rent])

    assert [(target["target"], target["rentalDeposit"], target["monthlyRent"])
            for row in tree["data"]["supplyRows"] for target in row["targets"]] == [
        ("고령자", 31160000, 123860), ("고령자(주거약자)", 31160000, 123860)]
    assert tree["data"]["unresolvedFields"] == []


def test_name_cut_at_page_break_joins_only_the_complex_that_has_that_type():
    quantity = table(4, [
        ["용인 죽전", "26A1", "대학생", "1", "26.25", "16.7"],
        ["용인 영덕", "17A1", "청년", "5", "17.76", "11.8"],
    ])
    rent = table(12, [["용인", "17A1", "청년", None, "27,880", "111,130"]], header=RENT_HEADER)

    _, tree = assemble([quantity, rent])

    rents = {target["target"]: target["rentalDeposit"] for row in tree["data"]["supplyRows"] for target in row["targets"]}
    assert rents == {"대학생": None, "청년": 27880000}
    assert {item["path"] for item in tree["data"]["unresolvedFields"]} == {
        "supplyRows[0].targets[0].rentalDeposit", "supplyRows[0].targets[0].monthlyRent"}


def test_rent_rows_that_match_no_supply_row_and_tableless_next_page_are_warned():
    quantity = table(6, [["Alpha", "26B", "청년", "5", "26.73", "13.07"]])
    rent = table(13, [
        ["Alpha", "26B", "청년", None, "40,000", "150,000"],
        ["연천 BIX", "36A2", "청년", None, "60,000", "250,000"],
    ], header=RENT_HEADER)

    grouped, tree = assemble([quantity, rent], tableless_pages=[7, 14])

    assert grouped.warnings == [
        "원본 7쪽의 공급표를 표로 읽지 못해 공급행이 빠졌을 수 있습니다. 원본을 확인해 주세요.",
        "원본 13쪽 임대조건 행을 공급행과 잇지 못했습니다. 원본을 확인해 주세요.",
    ]
    assert tree["data"]["supplyRows"][0]["targets"][0]["rentalDeposit"] == 40000000


def test_conflicting_rent_rows_for_one_target_stay_unresolved():
    quantity = table(4, [["Alpha", "26B", "청년", "5", "26.73", "13.07"]])
    rent = table(8, [
        ["Alpha", "26B", "청년", None, "40,000", "150,000"],
        ["Alpha", "26B", "청년", None, "41,000", "151,000"],
    ], header=RENT_HEADER)

    _, tree = assemble([quantity, rent])

    target = tree["data"]["supplyRows"][0]["targets"][0]
    assert (target["rentalDeposit"], target["monthlyRent"]) == (None, None)
    assert {item["reason"] for item in tree["data"]["unresolvedFields"]} == {
        "같은 대상의 임대조건이 여러 줄이라 하나로 정하지 못함"}


def test_tables_the_code_cannot_place_are_left_to_the_model():
    area_table = table(3, [["39", "44", "39.85", "15.5"]],
                       header=[["주택형", "건설호수", "주거전용", "주거공용"]])
    quantity = table(3, [["Alpha", "39", "대학생", "35", "39.85", "15.5"]])

    assert supply_grouping.group([quantity, area_table], row_values([quantity, area_table]), []) is None
    rent_only = table(4, [["Alpha", "39", "대학생", None, "56,780", "260,240"]], header=RENT_HEADER)
    assert supply_grouping.group([rent_only], row_values([rent_only]), []) is None
    # 공급 열이 없는 불확실한 표(소득 기준표)는 무시하고 나머지를 코드로 묶는다.
    income = table(17, [["1 인", "4,576,036 원 이하"]], header=[["가구원수", "기준"]], ambiguous=True)
    assert supply_grouping.group([quantity, income], row_values([quantity, income]), []) is not None


def rents(tree):
    return [(target["target"], target["rentalDeposit"], target["monthlyRent"])
            for row in tree["data"]["supplyRows"] for target in row["targets"]]


def unresolved_paths(tree):
    return {item["path"] for item in tree["data"]["unresolvedFields"]}


def test_rent_table_continued_on_next_page_keeps_the_header_unit():
    quantity = table(4, [["Alpha", "26B", "청년", "5", "26.73", "13.07"], [None, "36A", "고령자", "2", "36.9", "18.0"]])
    rent8 = table(8, [["Alpha", "26B", "청년", None, "40,000", "150,000"]], header=RENT_HEADER)
    rent9 = table(9, [[None, "36A", "고령자", None, "50,000", "200,000"]], inherit=rent8)

    _, tree = assemble([quantity, rent8, rent9])

    assert rents(tree) == [("청년", 40000000, 150000), ("고령자", 50000000, 200000)]
    assert tree["data"]["unresolvedFields"] == []


def test_rent_without_a_unit_in_the_header_is_left_unresolved():
    quantity = table(4, [["Alpha", "26B", "청년", "5", "26.73", "13.07"]])
    rent = table(8, [["Alpha", "26B", "청년", None, "40,000", "150,000"]],
                 header=[["단지명", "청약구분", "공급대상", None, "임대보증금", "월임대료"]])

    _, tree = assemble([quantity, rent])

    assert rents(tree) == [("청년", None, None)]
    assert unresolved_paths(tree) == {"supplyRows[0].targets[0].rentalDeposit", "supplyRows[0].targets[0].monthlyRent"}


def test_income_split_rows_continued_on_next_page_are_still_two_targets():
    quantity = table(4, [["Alpha", "24ABC", "청년", "20", "24.97", "15.37"]])
    rent8 = table(8, [["Alpha", "24ABC", "청년", "소득 없음", "46,920", "178,670"]], header=RENT_HEADER)
    rent9 = table(9, [["", "", "", "소득 있음", "49,680", "189,910"]], inherit=rent8)

    _, tree = assemble([quantity, rent8, rent9])

    assert rents(tree) == [("청년(소득 없음)", 46920000, 178670), ("청년(소득 있음)", 49680000, 189910)]
    assert len(tree["sharedCounts"]) == 1 and tree["sharedCounts"][0]["count"] == 20


def test_income_split_rows_in_a_table_with_counts_and_rents_share_the_count():
    header = [["단지명", "청약구분", "공급대상", None, "모집호수 예비자", "임대보증금(천원)", "월임대료(원)"]]
    combined = table(4, [
        ["Alpha", "24ABC", "청년", "소득 없음", "20", "46,920", "178,670"],
        [None, None, None, "소득 있음", None, "49,680", "189,910"],
    ], header=header)

    _, tree = assemble([combined])

    assert rents(tree) == [("청년(소득 없음)", 46920000, 178670), ("청년(소득 있음)", 49680000, 189910)]
    assert [(item["paths"], item["count"]) for item in tree["sharedCounts"]] == [(
        ["supplyRows[0].targets[0].reserveCount", "supplyRows[0].targets[1].reserveCount"], 20)]


def test_a_differing_rent_line_without_an_income_column_is_not_dropped():
    quantity = table(4, [["Alpha", "26B", "청년", "5", "26.73", "13.07"]])
    rent = table(8, [
        ["Alpha", "26B", "청년", "40,000", "150,000"],
        [None, None, None, "42,000", "160,000"],
    ], header=[["단지명", "청약구분", "공급대상", "임대보증금(천원)", "월임대료(원)"]])

    _, tree = assemble([quantity, rent])

    assert rents(tree) == [("청년", None, None)]
    assert unresolved_paths(tree) == {"supplyRows[0].targets[0].rentalDeposit", "supplyRows[0].targets[0].monthlyRent"}


def test_per_target_recruitment_counts_are_not_used_as_the_supply_row_total():
    header = [["단지명", "청약구분", "공급대상", "모집호수", "주거전용", "주거공용"]]
    quantity = table(4, [["Alpha", "26A", "청년", "5", "26.1", "13.0"], [None, None, "신혼부부", "5", None, None]],
                     header=header)
    merged = table(5, [["Beta", "26A", "청년", "8", "26.1", "13.0"], [None, None, "신혼부부", None, None, None]],
                   header=header)

    _, tree = assemble([quantity])
    _, merged_tree = assemble([merged])

    assert tree["data"]["supplyRows"][0]["totalSupplyHouseholdCount"] is None
    assert "supplyRows[0].totalSupplyHouseholdCount" in unresolved_paths(tree)
    # 한 칸이 그 주택형의 모든 대상을 덮으면 공급행 전체 모집수다.
    assert merged_tree["data"]["supplyRows"][0]["totalSupplyHouseholdCount"] == 8


def test_subtotal_and_total_rows_are_not_supply_targets():
    quantity = table(4, [
        ["Alpha", "26B", "청년", "5", "26.73", "13.07"],
        [None, None, "계", "5", None, None],
        [None, "36A", "고령자", "", "36.9", "18.0"],
        ["합계", None, None, "35", None, None],
    ])

    _, tree = assemble([quantity])

    assert targets(tree) == [("Alpha", "26B", "청년"), ("Alpha", "36A", "고령자")]
    assert tree["data"]["supplyRows"][1]["targets"][0]["reserveCount"] is None


def test_cells_that_are_not_plain_numbers_are_unresolved_instead_of_dropped():
    quantity = table(4, [["Alpha", "26B", "청년", "20호", "26.73㎡", "13.07"]])
    rent = table(8, [["Alpha", "26B", "청년", None, "46,920천원", "178,670"]], header=RENT_HEADER)

    _, tree = assemble([quantity, rent])

    assert {"supplyRows[0].exclusiveArea", "supplyRows[0].targets[0].reserveCount",
            "supplyRows[0].targets[0].rentalDeposit"} <= unresolved_paths(tree)
    assert tree["data"]["supplyRows"][0]["commonArea"] == 13.07


def test_converted_deposit_is_read_only_from_an_increase_limit_column():
    quantity = table(4, [["Alpha", "26B", "청년", "5", "26.73", "13.07"], [None, "36A", "고령자", "2", "36.9", "18.0"]])
    limit = table(8, [["Alpha", "26B", "청년", None, "40,000", "150,000", "15,000"]],
                  header=[[*RENT_HEADER[0], "전환가능 보증금 한도액(천원)"]])
    maximum = table(8, [["Alpha", "26B", "청년", None, "40,000", "150,000", "55,000"]],
                    header=[[*RENT_HEADER[0], "최대전환시 임대보증금(천원)"]])
    maximum_next = table(9, [[None, "36A", "고령자", None, "50,000", "200,000", "65,000"]], inherit=maximum)

    limit_grouped, limit_tree = assemble([quantity, limit])
    grouped, tree = assemble([quantity, maximum, maximum_next])

    assert limit_tree["data"]["supplyRows"][0]["targets"][0]["convertedDeposit"] == 15000000
    assert not any("전환보증금" in warning for warning in limit_grouped.warnings)
    # 한도가 아닌 열은 대상마다 미확인을 쌓지 않고 표 단위 경고 1줄로 알린다(사용자 결정).
    assert [row["targets"][0]["convertedDeposit"] for row in tree["data"]["supplyRows"]] == [None, None]
    assert not any(path.endswith("convertedDeposit") for path in unresolved_paths(tree))
    assert [warning for warning in grouped.warnings if "전환보증금" in warning] == [
        "원본 8, 9쪽 전환보증금 열은 증액 한도가 아닌 전환 뒤 금액이라 비워 두었습니다. 필요하면 원본에서 확인해 주세요."]


def test_uncertain_or_empty_supply_tables_are_left_to_the_model():
    quantity = table(4, [["Alpha", "26B", "청년", "5", "26.73", "13.07"]])
    uncertain = table(5, [["Alpha", "36A", "고령자", "2", "36.9", "18.0"]], ambiguous=True)
    empty = table(4, [["Alpha", "26B", "청년", "-", "-", "-"]])

    assert supply_grouping.group([quantity, uncertain], row_values([quantity, uncertain]), []) is None
    assert supply_grouping.group([empty], row_values([empty]), []) is None


def test_a_header_with_a_number_is_not_read_as_a_supply_row():
    quantity = table(4, [["Alpha", "26B", "청년", "5", "26.73", "13.07"]],
                     header=[["단지명", "청약구분", "공급대상", "예비자(2순위)", "주거전용", "주거공용"]])

    _, tree = assemble([quantity])

    assert targets(tree) == [("Alpha", "26B", "청년")]


def test_income_line_without_its_own_values_is_still_a_target_sharing_the_merged_count():
    header = [["단지명", "청약구분", "공급대상", None, "모집호수 예비자", "주거전용"]]
    quantity = table(4, [
        ["Alpha", "24A", "청년", "소득 없음", "20", "24.9"],
        [None, None, None, "소득 있음", None, None],
    ], header=header)

    _, tree = assemble([quantity])

    assert targets(tree) == [("Alpha", "24A", "청년(소득 없음)"), ("Alpha", "24A", "청년(소득 있음)")]
    assert [(item["paths"], item["count"]) for item in tree["sharedCounts"]] == [(
        ["supplyRows[0].targets[0].reserveCount", "supplyRows[0].targets[1].reserveCount"], 20)]


def test_a_first_supply_row_without_a_plain_number_is_left_to_the_model():
    quantity = table(4, [
        ["Alpha", "26A", "청년", "39(3)", "26.4㎡", "13.1㎡"],
        [None, "36A", "고령자", "5", "36.9", "18.0"],
    ])

    assert supply_grouping.group([quantity], row_values([quantity]), []) is None


def test_merged_rent_cells_on_an_income_line_take_the_value_above():
    header = [["단지명", "청약구분", "공급대상", None, "모집호수 예비자", "임대보증금(천원)", "월임대료(원)"]]
    combined = table(4, [
        ["Alpha", "24A", "청년", "소득 없음", "20", "46,920", "178,670"],
        [None, None, None, "소득 있음", None, None, None],
    ], header=header)

    _, tree = assemble([combined])

    assert rents(tree) == [("청년(소득 없음)", 46920000, 178670), ("청년(소득 있음)", 46920000, 178670)]
    assert tree["data"]["unresolvedFields"] == []


def test_income_split_in_both_count_and_rent_tables_is_left_to_the_model():
    header = [["단지명", "청약구분", "공급대상", None, "모집호수 예비자", "주거전용"]]
    quantity = table(4, [
        ["Alpha", "24A", "청년", "소득 없음", "20", "24.9"],
        [None, None, None, "소득 있음", None, None],
    ], header=header)
    rent = table(8, [
        ["Alpha", "24A", "청년", "소득 없음", "46,920", "178,670"],
        [None, None, None, "소득 있음", "49,680", "189,910"],
    ], header=RENT_HEADER)

    assert supply_grouping.group([quantity, rent], row_values([quantity, rent]), []) is None


def test_merged_area_cells_take_the_area_above_not_the_complex_row_area():
    # 경기행복주택 공고: 파주 35AB는 위 35A1,B1과 면적 칸이 병합돼 있고, 평택 26A1-1·26A1-2는 쪽을 넘은 병합 칸이라 빈칸이다.
    page4 = table(4, [
        ["파주", "26A", "청년", "10", "26.7900", "18.8200"],
        [None, "35A1,B1", "청년", "10", "35.0200", "24.4800"],
        [None, "35AB", "고령자", "10", None, None],
        ["평택", "26A1", "청년", "40", "26.7300", "13.0781"],
    ])
    page5 = table(5, [
        ["", "26A1-1", "주거급여 수급자", "5", "", ""],
        [None, "26A1-2", "고령자", "2", None, None],
        [None, "26B", "청년", "5", "26.7300", "13.0781"],
    ], inherit=page4)

    _, tree = assemble([page4, page5])

    areas = {row["sourceHousingTypeName"]: row["exclusiveArea"] for row in tree["data"]["supplyRows"]}
    assert areas == {"26A": 26.79, "35A1,B1": 35.02, "35AB": 35.02, "26A1": 26.73, "26A1-1": 26.73, "26A1-2": 26.73,
                     "26B": 26.73}
    # 원문 대조가 물려받은 면적을 지우지 않도록 면적이 적힌 행을 근거 행에 넣는다.
    assert "P4:T1:R3" in next(item["rowIds"] for item in tree["sourceRows"] if item["path"] == "supplyRows[2]")
    assert tree["data"]["unresolvedFields"] == []


def test_a_merged_looking_area_is_not_taken_from_the_row_above_when_the_complex_changes():
    page4 = table(4, [["파주", "26A", "청년", "10", "26.7900", "18.8200"]])
    page5 = table(5, [["수원", "17A", "청년", "1", "", ""]], inherit=page4)

    _, tree = assemble([page4, page5])

    assert tree["data"]["supplyRows"][1]["exclusiveArea"] is None


def test_a_new_table_with_its_own_header_does_not_take_the_area_of_the_previous_table():
    page4 = table(4, [["파주", "26A", "청년", "10", "26.7900", "18.8200"]])
    page5 = table(5, [["", "17A", "청년", "1", "", ""]])

    _, tree = assemble([page4, page5])

    assert tree["data"]["supplyRows"][1]["exclusiveArea"] is None


def test_an_area_inherited_twice_keeps_every_row_it_came_from():
    # 위 행이 전용은 물려받고 공용은 자기 칸에 있으면, 아래 행의 공용 근거는 그 위 행이다.
    page4 = table(4, [
        ["파주", "35A1", "청년", "10", "35.0200", "24.4800"],
        [None, "35A2", "청년", "5", None, "24.5000"],
        [None, "35AB", "고령자", "10", None, None],
    ])

    _, tree = assemble([page4])

    row = tree["data"]["supplyRows"][2]
    assert (row["exclusiveArea"], row["commonArea"]) == (35.02, 24.5)
    assert {"P4:T1:R2", "P4:T1:R3"} <= set(next(item["rowIds"] for item in tree["sourceRows"] if item["path"] == "supplyRows[2]"))


def test_a_blank_area_on_the_first_row_of_the_next_page_is_not_filled_when_the_row_has_its_own_area():
    page4 = table(4, [["Alpha", "36AB1", "청년", "3", "36.2643", "20.9058"]])
    page5 = table(5, [["", "44A", "고령자", "3", "44.1269", ""]], inherit=page4)

    _, tree = assemble([page4, page5])

    assert tree["data"]["supplyRows"][1]["commonArea"] is None


def test_code_grouped_housing_type_row_gets_subtypes_from_its_house_type_column():
    quantity = table(3, [
        ["Alpha", "059.9000A", "59A", "청년", "10", "59.98", "21.5309"],
        [None, None, "59A1", None, None, None, None],
        [None, None, "59C", None, None, "59.99", "21.5345"],
    ], header=[["단지명", "청약구분", "주택타입", "공급대상", "모집호수 예비자", "주거전용", "주거공용"]])

    _, tree = assemble([quantity])

    row, = tree["data"]["supplyRows"]
    assert (row["sourceHousingTypeName"], row["exclusiveArea"]) == ("059.9000A", 59.98)
    assert [(item["name"], item["exclusiveArea"], item["commonArea"]) for item in row["subtypes"]] == [
        ("59A·59A1", 59.98, 21.5309), ("59C", 59.99, 21.5345)]
