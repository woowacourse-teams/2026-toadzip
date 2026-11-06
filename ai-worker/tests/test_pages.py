import time
from types import SimpleNamespace

import pymupdf
import pytest

from ai_worker import pages
from ai_worker.extraction import PermanentError

COVER = "여수미평1 영구임대주택 예비입주자 모집 (입주자모집공고일 : 2026. 10. 20.)"
SUPPLY = "형별 공급계획 전용 주거 공용 26.37 298 26.37 13.50 39.87 31.32 239 31.32 12.60 43.92"
RENT = "임대조건 임대보증금 월 임대료 26.37 2,520,000 126,000 2,394,000 50,160"
QUALIFICATION = "3. 신청자격 입주자 모집공고일 현재 무주택세대구성원"
SCHEDULE = "5. 공급일정 및 세부절차 신청기간 ‘26.11.10(화)~11.12(목) 09:00~18:00"
CONTACT = "12. 문의처 신청문의 여수미평1관리소 (061-652-4568)"
NOTICE = "8. 유의사항 이 주택의 임대차 계약기간은 2년이며"


def pdf(*texts: str) -> bytes:
    document = pymupdf.open()
    for text in texts:
        document.new_page().insert_text((20, 72), text, fontname="korea", fontsize=6)
    return document.tobytes()


def test_selects_first_page_and_pages_with_sections():
    selection = pages.select([COVER, NOTICE, SUPPLY, NOTICE, RENT, NOTICE, SCHEDULE, NOTICE, CONTACT])

    assert selection == pages.PageSelection([1, 3, 5, 7, 9], False)


def test_selects_page_after_supply_and_rent_tables_that_may_continue():
    assert pages.select([COVER, SUPPLY + " " + RENT, QUALIFICATION, NOTICE]).pages == [1, 2, 3]


def test_skips_form_pages_even_with_keywords():
    assert pages.select([COVER, SUPPLY, "[별지1] 공급신청서 " + RENT, RENT]).pages == [1, 2, 4]


def test_skips_form_page_with_leading_page_number_footer():
    selection = pages.select([COVER, SUPPLY, RENT, NOTICE, "- 5 - [별지2] 개인정보 수집 동의서 입주자선정방법"])

    assert selection.pages == [1, 2, 3]


def test_finds_rent_table_in_thousands_of_won():
    rent_in_thousands = "금회모집 임대조건 임대보증금(천원) 월임대료 (원) 67,400 3,370 64,030 342,610"

    assert pages.select([COVER, SUPPLY, rent_in_thousands]) == pages.PageSelection([1, 2, 3], False)


def test_uses_whole_document_without_supply_or_rent_table():
    assert pages.select([COVER, SUPPLY, NOTICE]) == pages.PageSelection([1, 2, 3], True)


def test_prose_words_without_values_do_not_select_page():
    prose = "당첨자 발표일이 동일한 주택 입주자선정제한 주거전용면적은 주거의 용도로만 쓰이는 면적"

    assert pages.select([COVER, SUPPLY, RENT, NOTICE, prose, "문의처는 콜센터입니다"]).pages == [1, 2, 3]


def test_finds_sections_with_spaced_or_doubled_letters():
    spaced_rent = "임 대 보 증 금 월 임 대 료 2,520,000 2,394,000 50,160"
    doubled_supply = "공공공공용용용용 26.37 31.32 55.83"

    assert pages.select([COVER, NOTICE, doubled_supply, NOTICE, spaced_rent, NOTICE]).pages == [1, 3, 5]


def test_keeps_complete_sections_and_stops_before_other_sections_and_attached_forms():
    texts = [COVER, "2. 임대대상 및 임대조건\n" + SUPPLY,
             "26B 청년 30 26.44 15.24 57.59", "26C 고령자 2 26.50 15.30 57.80",
             "36A2 청년 10 36.59 20.32 82.10", RENT,
             "3. 신청자격 및 입주자 선정방법\n대학생 자격", "청년 자격의 계속", "고령자 자격의 계속",
             "4. 공급일정 및 신청방법\n" + SCHEDULE,
             "5. 신청서류\n제출할 서류 목록",
             "9. 임대센터 위치안내\n다산역 센터 031-510-0036", "용인 센터 031-8081-3500",
             "[붙임 1] 개인정보 수집 동의서\n선정순위 결정"]

    with pages.open_pdf(pdf(*texts)) as document:
        selection = pages.select(pages.page_texts(document, later()))
        prompt = pages.prompt_pages(document, selection.pages, later()).text

    assert set(range(2, 11)).issubset(selection.pages)
    assert {12, 13}.issubset(selection.pages)
    assert 11 not in selection.pages
    assert 14 not in selection.pages
    assert "36A2 청년 10" in prompt
    assert "고령자 자격의 계속" in prompt
    assert "031-8081-3500" in prompt


def test_headerless_table_continues_for_more_than_one_following_page():
    continuation = "26B 청년 30 26.44 15.24 57.59"

    selection = pages.select([COVER, SUPPLY, continuation, continuation, continuation, RENT, NOTICE])

    assert {2, 3, 4, 5}.issubset(selection.pages)


def test_table_of_contents_does_not_open_a_section_spanning_unrelated_pages():
    toc = "목차\n1. 공급대상 ······· 2\n2. 신청자격 ······· 15\n3. 문의처 ······· 30"

    selection = pages.select([COVER, toc, "그림만 있는 안내", SUPPLY, RENT, NOTICE])

    assert 3 not in selection.pages


def test_a_supply_table_sharing_a_page_with_the_contents_is_kept():
    toc = "목차\n1. 공급대상 ······· 2\n2. 신청자격 ······· 15\n3. 문의처 ······· 30"

    selection = pages.select([COVER, toc + "\n" + SUPPLY, RENT, NOTICE])

    assert not selection.whole_document
    assert 2 in selection.pages


def test_continuation_above_the_next_section_heading_is_kept():
    selection = pages.select([COVER, SUPPLY, RENT, QUALIFICATION,
                              "고령자 자격의 마지막 문단\n4. 유의사항", "그림 안내"])

    assert 5 in selection.pages
    assert 6 not in selection.pages


def test_a_body_reference_to_an_attached_form_does_not_drop_eligibility():
    qualification = "3. 신청자격\n청년은 [붙임1] 신청서를 제출한다. 나이 19~39세"
    selection = pages.select([COVER, SUPPLY, RENT, qualification, "고령자는 65세 이상", "4. 유의사항"])

    assert {4, 5}.issubset(selection.pages)


def test_last_contact_section_keeps_all_pages_until_document_end():
    selection = pages.select([COVER, SUPPLY, RENT, "9. 문의처\n다산센터 031-510-0036", "용인센터 031-8081-3500"])

    assert {4, 5}.issubset(selection.pages)


def test_numeric_examples_in_the_next_section_do_not_extend_the_supply_table():
    selection = pages.select([COVER, SUPPLY, "3. 유의사항\n면적 예시 26.44 15.24 57.59",
                              "숫자 예시 계속 26.44 15.24 57.59", RENT])

    assert 3 not in selection.pages
    assert 4 not in selection.pages


def table_pdf():
    document = pymupdf.open()
    page = document.new_page()
    xs, ys = [20, 140, 240, 340, 450], [20, 50, 80, 110]
    for x in xs:
        page.draw_line((x, ys[0]), (x, ys[-1]))
    for y in ys:
        page.draw_line((xs[1] if y == 80 else xs[0], y), (xs[-1], y))
    for row, values in enumerate([
        ["주택형 / 전용", "공급계층", "임대보증금(천원)", "월임대료(원)"],
        ["26A 26.4422", "청년 소득 없음", "46,920", "178,670"],
        ["", "청년 소득 있음", "49,680", "189,910"],
    ]):
        for col, value in enumerate(values):
            page.insert_text((xs[col] + 4, ys[row] + 18), value, fontname="korea", fontsize=7)
    return document.tobytes()


def alternative_supply_table_pdf():
    document = pymupdf.open()
    page = document.new_page()
    xs, ys = [20, 120, 220, 320, 420, 520], [20, 50, 80]
    for x in xs:
        page.draw_line((x, ys[0]), (x, ys[-1]))
    for y in ys:
        page.draw_line((xs[0], y), (xs[-1], y))
    for row_index, row in enumerate([
        ["단지명", "공급유형", "모집세대수", "주거전용", "주거공용"],
        ["단지A", "행복주택", "10", "26.44", "15.20"],
    ]):
        for column, value in enumerate(row):
            page.insert_text((xs[column] + 3, ys[row_index] + 18), value, fontname="korea", fontsize=7)
    return document.tobytes()


def test_real_table_keeps_decimal_amount_units_and_merged_cells():
    with pages.open_pdf(table_pdf()) as document:
        prompt, tables = pages.page_with_tables(document[0])

    assert "26.4422" in prompt and "46,920" in prompt
    assert tables[0][0][2] == "임대보증금(천원)"
    assert tables[0][1] == ["26A 26.4422", "청년 소득 없음", "46,920", "178,670"]
    assert tables[0][2] == [None, "청년 소득 있음", "49,680", "189,910"]


def test_alternate_supply_headers_are_routed_to_the_supply_stage():
    with pages.open_pdf(alternative_supply_table_pdf()) as document:
        split = pages.prompt_pages(document, [1], later())

    assert "공급유형" in split.supply_text
    assert "모집세대수" in split.supply_text
    assert "[P1:T1:R2]" in split.supply_text
    assert "단지A" in split.complex_candidates
    assert "단지A" not in split.notice_text
    assert not split.table_structure_fallback


def test_split_table_headers_map_reserve_count_to_its_own_column():
    rows = [
        ["단지명", "공급형", None, "공급 대상", "모집 호수", "세대 당 계약면적(㎡)", None],
        [None, "주택 구분", "청약 구분", None, "예비자", "주거 전용", "주거 공용"],
        [None, None, None, None, "일반 공급", None, None],
        ["Test", "26", "26A", "청년", "20", "26.0000", "15.0000"],
    ]

    values = pages.table_row_field_values(rows, 3)

    assert values == {"complexName": "Test", "housingType": "26A", "target": "청년", "reserveCount": "20",
                      "exclusiveArea": "26.0000", "commonArea": "15.0000"}


def test_table_header_mapping_distinguishes_base_rent_from_conversion_options():
    rows = [
        ["공급형", "공급대상", "임대조건", None, None, None, "임대보증금 최소화", "", "임대보증금 최대화", ""],
        ["", "", "임대보증금", "", "", "월 임대료", "임대보증금", "월임대료", "임대보증금", "월임대료"],
        ["39", "대학생", "계", "계약금", "잔금", None, None, None, None, None],
        [None, None, "56,780,000", "2,839,000", "53,941,000", "260,240", "9,780,000", "397,320",
         "87,780,000", "105,240"],
    ]

    columns = pages.table_field_columns(rows)

    assert columns["rentalDeposit"] == 2
    assert columns["monthlyRent"] == 5
    assert columns["convertedDeposit"] == 8


def test_continued_table_page_reuses_the_previous_page_header_columns():
    headers = [
        ["단지명", "공급형", None, "공급 대상", "모집 호수", "세대 당 계약면적(㎡)", None],
        [None, "주택 구분", "청약 구분", None, "예비자", "주거 전용", "주거 공용"],
        [None, None, None, None, "일반 공급", None, None],
    ]
    continuation_rows = [["Test", "26", "26A", "청년", "20", "26.0000", "15.0000"]]

    values = pages.table_row_field_values(continuation_rows, 0, pages.table_field_columns(headers))

    assert values == {"complexName": "Test", "housingType": "26A", "target": "청년", "reserveCount": "20",
                      "exclusiveArea": "26.0000", "commonArea": "15.0000"}


def test_prompt_pages_carries_header_columns_to_a_real_continuation_table_page(monkeypatch):
    first_page = [
        ["단지명", "공급형", "", "공급대상", "모집호수", "계약면적", "", "", "", "", "최대거주기간", "난방", "층수", "총세대수"],
        ["", "주택구분", "청약구분", "", "예비자", "주거전용", "주거공용", "", "", "합계", "", "", "", ""],
        ["", "", "", "", "일반공급", "", "", "기타공용", "주차장", "", "", "", "", ""],
        ["Test", "26", "26A", "청년", "20", "26.0000", "15.0000", "2.0000", "13.0000", "55.0000",
         "10년", "개별난방", "5층", "100세대"],
    ]
    continuation = [
        ["", "26", "26A", "청년", "20", "26.0000", "15.0000", "2.0000", "13.0000", "55.0000", "10년", "개별난방", "5층", "100세대"],
        ["", "26", "26B", "고령자", "3", "26.0000", "15.0000", "2.0000", "13.0000", "55.0000", "10년", "개별난방", "5층", "100세대"],
    ]
    matrix = {0: first_page, 1: continuation}

    def detected(page):
        return [pages.DetectedTable(matrix[page.number], tuple(page.rect))], False

    monkeypatch.setattr(pages, "detected_tables", detected)
    with pages.open_pdf(continued_supply_pdf()) as document:
        split = pages.prompt_pages(document, [1, 2], time.monotonic() + 30)

    assert all(not split.supply_row_values[f"P1:T1:R{row}"] for row in (1, 2, 3))
    assert split.supply_row_values["P2:T1:R1"] == {
        "housingType": "26A", "target": "청년", "reserveCount": "20",
        "exclusiveArea": "26.0000", "commonArea": "15.0000", "totalHouseholdCount": "100세대",
    }


def continued_supply_pdf() -> bytes:
    document = pymupdf.open()
    headers = [
        ["단지명", "공급형", "", "공급대상", "모집호수", "계약면적", "", "", "", "", "최대거주기간", "난방", "층수", "총세대수"],
        ["", "주택구분", "청약구분", "", "예비자", "주거전용", "주거공용", "", "", "합계", "", "", "", ""],
        ["", "", "", "", "일반공급", "", "", "기타공용", "주차장", "", "", "", "", ""],
    ]
    _draw_grid(document.new_page(), headers + [["Test", "26", "26A", "청년", "20", "26.0000", "15.0000", "2.0000",
                                                "13.0000", "55.0000", "10년", "개별난방", "5층", "100세대"]])
    _draw_grid(document.new_page(), [
        ["", "26", "26A", "청년", "20", "26.0000", "15.0000", "2.0000", "13.0000", "55.0000", "10년", "개별난방", "5층", "100세대"],
        ["", "26", "26B", "고령자", "3", "26.0000", "15.0000", "2.0000", "13.0000", "55.0000", "10년", "개별난방", "5층", "100세대"],
    ])
    return document.tobytes()


def _draw_grid(page, rows: list[list[str]]) -> None:
    width = 38 if len(rows[0]) > 10 else 80
    xs = [20 + index * width for index in range(len(rows[0]) + 1)]
    ys = [50 + index * 25 for index in range(len(rows) + 1)]
    for x in xs:
        page.draw_line((x, ys[0]), (x, ys[-1]))
    for y in ys:
        page.draw_line((xs[0], y), (xs[-1], y))
    for row_index, row in enumerate(rows):
        for column, value in enumerate(row):
            if value:
                page.insert_text((xs[column] + 2, ys[row_index] + 16), value, fontname="korea",
                                 fontsize=4 if len(rows[0]) > 10 else 6)


def test_empty_coordinate_read_keeps_page_text_instead_of_a_table_with_missing_values(monkeypatch):
    original = pymupdf.Page.get_text

    def empty_cell(page, *args, **kwargs):
        return "" if "clip" in kwargs else original(page, *args, **kwargs)

    monkeypatch.setattr(pymupdf.Page, "get_text", empty_cell)
    with pages.open_pdf(table_pdf()) as document:
        prompt, tables = pages.page_with_tables(document[0])

    assert "46,920" in prompt
    assert tables == []


def test_reads_text_of_each_page():
    with pages.open_pdf(pdf(COVER, SUPPLY, CONTACT)) as document:
        texts = pages.page_texts(document, later())

    assert len(texts) == 3
    assert "공용" in texts[1]


def later() -> float:
    return time.monotonic() + 600


class Clock:
    """쪽 하나를 처리할 때마다 10초가 지난 것처럼 시각을 돌려준다."""

    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        self.now += 10
        return self.now


@pytest.mark.parametrize("read", [
    lambda document, deadline: pages.page_texts(document, deadline),
    lambda document, deadline: pages.prompt_pages(document, [1, 2, 3], deadline),
])
def test_stops_reading_pages_once_deadline_passes(monkeypatch, read):
    clock = Clock()
    monkeypatch.setattr(pages.time, "monotonic", clock)
    read_pages = []
    original = pymupdf.Page.get_text
    monkeypatch.setattr(pymupdf.Page, "get_text", lambda page, *args, **kwargs: read_pages.append(page.number) or original(page, *args, **kwargs))

    with pages.open_pdf(pdf(COVER, SUPPLY, RENT)) as document:
        with pytest.raises(PermanentError) as error:
            read(document, 1015)

    assert error.value.code == "PDF_TOO_SLOW"
    assert set(read_pages) == {0, 1}


def test_prompt_numbers_selected_pages_in_order():
    with pages.open_pdf(pdf("cover", "notice", "supply", "contact")) as document:
        text = pages.prompt_pages(document, [1, 3, 4], later()).text

    assert text.index("[첨부 1쪽]\ncover") < text.index("[첨부 2쪽]\nsupply") < text.index("[첨부 3쪽]\ncontact")
    assert "notice" not in text


# 인천부평 행복주택 공고의 임대조건표 칸을 PyMuPDF가 뽑은 모양 그대로 줄였다. 병합 칸은 None이다.
RENT_CELLS = [
    ["공급\n형별", "공급\n계층", "임대조건", None, None, None, "전환가능\n보증금\n한도액\n(원)"],
    [None, None, "임대보증금(원)", None, None, "월\n임대료\n(원)", None],
    ["16A", "대학생\n·\n청년(소득無)", "31786000\n, ,", "1589000\n, ,", "30197000\n, ,", "138640\n,", "(+) 9000000\n, ,"],
    [None, "청년(소득有)", "33652000\n, ,", "1682000\n, ,", "31970000\n, ,", "146790\n,", "(+) 9000000\n, ,"],
    [None, "기존거주자\n(철거민)", "37640000\n, ,", "1882000\n, ,", "35758000\n, ,", "163100\n,", "(+) 9000000\n, ,"],
    [None, None, None, None, None, None, "( ) 31000000\n- , ,"],
    ["21A", "기존거주자\n(철거민)", "49240000\n, ,", "2462000\n, ,", "46778000\n, ,", "213370\n,", "(+) 19000000\n, ,"],
]


def test_counts_rental_rows_by_housing_type_and_target_filling_merged_cells():
    assert pages.rental_keys(RENT_CELLS) == {
        ("16A", "대학생·청년(소득無)"), ("16A", "청년(소득有)"), ("16A", "기존거주자(철거민)"), ("21A", "기존거주자(철거민)"),
    }


def test_tables_without_rent_header_are_not_counted():
    assert pages.rental_keys([["형별", "공급호수"], ["16A", "78", "31786000"]]) == set()


def test_rental_rows_are_counted_once_across_selected_pages(monkeypatch):
    table = SimpleNamespace(to_markdown=lambda: "|표|", extract=lambda: RENT_CELLS)
    calls = []
    monkeypatch.setattr(pymupdf.Page, "find_tables", lambda page: calls.append(page.number) or SimpleNamespace(tables=[table]))
    monkeypatch.setattr(pages, "table_cells", lambda page, table: RENT_CELLS)
    with pymupdf.open(stream=pdf("cover", "rent", "rent again"), filetype="pdf") as document:
        assert pages.prompt_pages(document, [1, 2, 3], later()).rental_rows == 4
    # 표 찾기는 쪽마다 1초 안팎이라 프롬프트와 검산이 한 번의 결과를 같이 쓴다.
    assert calls == [0, 1, 2]


def test_continued_table_rows_without_housing_type_are_not_counted():
    continued = [RENT_CELLS[0], RENT_CELLS[1], [None, "고령자", "48000000\n, ,", None, None, "210000\n,", None]]

    assert pages.rental_keys(continued) == set()


@pytest.mark.parametrize("content", [b"<html>not a pdf</html>", b"", b"%PDF-1.7 broken"])
def test_unreadable_pdf_is_permanent_error(content):
    with pytest.raises(PermanentError) as error:
        pages.open_pdf(content)

    assert error.value.code == pages.UNREADABLE_CODE


def test_encrypted_pdf_is_permanent_error():
    document = pymupdf.open()
    document.new_page().insert_text((72, 72), "secret")
    encrypted = document.tobytes(encryption=pymupdf.PDF_ENCRYPT_AES_256, user_pw="user", owner_pw="owner")

    with pytest.raises(PermanentError) as error:
        pages.open_pdf(encrypted)

    assert error.value.code == pages.UNREADABLE_CODE
