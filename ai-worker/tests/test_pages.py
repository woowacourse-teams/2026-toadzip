import time

import pymupdf
import pytest

from ai_worker import pages, tables
from ai_worker.extraction import PermanentError
from conftest import COVER, NOTICE, RENT, RENT_CELLS, SUPPLY, text_pdf as pdf

QUALIFICATION = "3. 신청자격 입주자 모집공고일 현재 무주택세대구성원"
SCHEDULE = "5. 공급일정 및 세부절차 신청기간 ‘26.11.10(화)~11.12(목) 09:00~18:00"
CONTACT = "12. 문의처 신청문의 여수미평1관리소 (061-652-4568)"
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
        split = pages.prompt_pages(document, selection.pages, later())

    assert set(range(2, 11)).issubset(selection.pages)
    assert {12, 13}.issubset(selection.pages)
    assert 11 not in selection.pages
    assert 14 not in selection.pages
    # 표 셀을 읽지 못한 쪽이라 공급 단계에는 고른 쪽 원문이, 본문 단계에는 본문이 간다.
    assert "36A2 청년 10" in split.supply_text
    assert "고령자 자격의 계속" in split.notice_text
    assert "031-8081-3500" in split.notice_text


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


def block_heading_tables_pdf():
    """블록 제목 아래 공급표를 두고, 그 아래 다른 블록 제목과 공급표를 둔 쪽(광주선운2 A-1·A-3블록)."""
    document = pymupdf.open()
    page = document.new_page()
    xs = [20, 120, 240, 340, 440, 560]
    for heading, top in (("■ 광주선운2 A-1블록 (총 336세대)", 60), ("■ 광주선운2 A-3블록 (총 147세대)", 220)):
        page.insert_text((20, top - 14), heading, fontname="korea", fontsize=8)
        ys = [top, top + 30, top + 60]
        for x in xs:
            page.draw_line((x, ys[0]), (x, ys[-1]))
        for y in ys:
            page.draw_line((xs[0], y), (xs[-1], y))
        for row_index, row in enumerate([["공급형별", "공급대상", "금회모집 예비자수", "임대보증금(천원)", "월임대료(원)"],
                                          ["55B-1", "신혼부부", "20", "67,400", "342,610"]]):
            for column, value in enumerate(row):
                page.insert_text((xs[column] + 3, ys[row_index] + 18), value, fontname="korea", fontsize=7)
    return document.tobytes()


def test_each_supply_row_remembers_the_text_above_its_table_on_the_page():
    with pages.open_pdf(block_heading_tables_pdf()) as document:
        split = pages.prompt_pages(document, [1], later())

    first, second = split.supply_tables
    above_first, above_second = (split.supply_row_headings[table.rows[1].id] for table in (first, second))
    assert "광주선운2 A-1블록" in above_first and "A-3블록" not in above_first
    # 아래 표 위의 글에는 두 제목이 다 있고, 표에 가까운 제목이 뒤에 온다(source_check.nearest_heading이 고른다).
    assert above_second.index("A-1블록") < above_second.index("A-3블록")


def test_real_table_keeps_decimal_amount_units_and_merged_cells():
    with pages.open_pdf(table_pdf()) as document:
        split = pages.prompt_pages(document, [1], later())

    assert "[P1:T1:R2] 26A 26.4422\t청년 소득 없음\t46,920\t178,670" in split.supply_text
    [table] = split.supply_tables
    assert table.cells[0][2] == "임대보증금(천원)"
    assert table.cells[1] == ["26A 26.4422", "청년 소득 없음", "46,920", "178,670"]
    assert table.cells[2] == [None, "청년 소득 있음", "49,680", "189,910"]


def test_alternate_supply_headers_are_routed_to_the_supply_stage():
    with pages.open_pdf(alternative_supply_table_pdf()) as document:
        split = pages.prompt_pages(document, [1], later())

    assert "공급유형" in split.supply_text
    assert "모집세대수" in split.supply_text
    assert "[P1:T1:R2]" in split.supply_text
    assert "단지A" in split.complex_candidates
    assert "단지A" not in split.notice_text
    assert not split.table_structure_fallback


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
        return [tables.DetectedTable(matrix[page.number], tuple(page.rect))], False

    monkeypatch.setattr(tables, "detected_tables", detected)
    with pages.open_pdf(continued_supply_pdf()) as document:
        split = pages.prompt_pages(document, [1, 2], time.monotonic() + 30)

    assert all(not split.supply_row_values[f"P1:T1:R{row}"] for row in (1, 2, 3))
    assert split.supply_row_values["P2:T1:R1"] == {
        "housingType": "26A", "target": "청년", "reserveCount": "20",
        "exclusiveArea": "26.0000", "commonArea": "15.0000", "totalHouseholdCount": "100세대",
    }


def test_prompt_pages_keeps_house_type_subtypes_under_the_housing_type_row_id(monkeypatch):
    cells = [
        ["주택형 (신청형)", "주택 타입", "주거 전용", "주거 공용", "금회 예비입주자 모집 호수"],
        ["059.9000B", "59B", "59.93", "21.5130", "25"],
        [None, "59B1", "59.92", "21.5094", None],
    ]
    monkeypatch.setattr(tables, "detected_tables", lambda page: ([tables.DetectedTable(cells, tuple(page.rect))], False))
    with pages.open_pdf(continued_supply_pdf()) as document:
        split = pages.prompt_pages(document, [1], time.monotonic() + 30)

    assert split.supply_row_subtypes == {"P1:T1:R2": (
        tables.TableSubtype("59B", 59.93, 21.513, None), tables.TableSubtype("59B1", 59.92, 21.5094, None))}


@pytest.mark.parametrize("repeated_header", [[], [["주택형", "주택타입", "주거전용", "주거공용", "예비자"]]])
def test_housing_type_continued_on_the_next_page_has_no_partial_subtypes(monkeypatch, repeated_header):
    first_page = [
        ["주택형", "주택타입", "주거전용", "주거공용", "예비자"],
        ["049.7600A", "49A", "49.76", "17.8623", "35"],
        ["059.9000A", "59A", "59.98", "21.5309", "125"],
    ]
    # 쪽을 넘은 병합 칸은 머리글 없는 이어짐 표 첫 행에서 빈칸('')으로 읽힌다.
    # 다음 쪽 표는 머리글을 다시 적을 수도 있다.
    continuation = [
        *repeated_header,
        ["", "59A1", "59.99", "21.5345", ""],
        ["059.9000B", "59B", "59.93", "21.5130", "25"],
    ]
    matrix = {0: first_page, 1: continuation}
    monkeypatch.setattr(tables, "detected_tables",
                        lambda page: ([tables.DetectedTable(matrix[page.number], tuple(page.rect))], False))
    with pages.open_pdf(continued_supply_pdf()) as document:
        split = pages.prompt_pages(document, [1, 2], time.monotonic() + 30)

    assert {row_id: [item.name for item in subtypes] for row_id, subtypes in split.supply_row_subtypes.items()} == {
        "P1:T1:R2": ["49A"], f"P2:T1:R{2 + len(repeated_header)}": ["59B"]}


def test_continued_housing_type_without_subtypes_keeps_the_subtypes_of_the_housing_type_before_it(monkeypatch):
    # 앞 쪽 마지막 신청형(059.9000A)은 면적을 읽지 못해 세부 타입이 없다. 그 앞 신청형(049.7600A)의 세부 타입은 남는다.
    first_page = [
        ["주택형", "주택타입", "주거전용", "주거공용", "예비자"],
        ["049.7600A", "49A", "49.76", "17.8623", "35"],
        ["059.9000A", "59A", "", "", "125"],
    ]
    continuation = [
        ["", "59A1", "59.99", "21.5345", ""],
        ["059.9000B", "59B", "59.93", "21.5130", "25"],
    ]
    matrix = {0: first_page, 1: continuation}
    monkeypatch.setattr(tables, "detected_tables",
                        lambda page: ([tables.DetectedTable(matrix[page.number], tuple(page.rect))], False))
    with pages.open_pdf(continued_supply_pdf()) as document:
        split = pages.prompt_pages(document, [1, 2], time.monotonic() + 30)

    assert {row_id: [item.name for item in subtypes] for row_id, subtypes in split.supply_row_subtypes.items()} == {
        "P1:T1:R2": ["49A"], "P2:T1:R2": ["59B"]}


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
        split = pages.prompt_pages(document, [1], later())

    assert "46,920" in split.notice_text and "46,920" in split.supply_text
    assert split.supply_tables == [] and split.supply_row_values == {}
    assert split.table_structure_fallback


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
        split = pages.prompt_pages(document, [1, 3, 4], later())

    for text in (split.notice_text, split.supply_text):
        assert (text.index("[첨부 1쪽 / 원본 1쪽]\ncover") < text.index("[첨부 2쪽 / 원본 3쪽]\nsupply")
                < text.index("[첨부 3쪽 / 원본 4쪽]\ncontact"))
        assert "notice" not in text


def test_rental_rows_are_counted_once_across_selected_pages(monkeypatch):
    calls = []
    monkeypatch.setattr(tables, "detected_tables", lambda page: calls.append(page.number) or (
        [tables.DetectedTable(RENT_CELLS, tuple(page.rect))], False))
    with pymupdf.open(stream=pdf("cover", "rent", "rent again"), filetype="pdf") as document:
        assert pages.prompt_pages(document, [1, 2, 3], later()).rental_rows == 4
    # 표 찾기는 쪽마다 1초 안팎이라 프롬프트와 검산이 한 번의 결과를 같이 쓴다.
    assert calls == [0, 1, 2]


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


def test_selects_a_separate_complex_overview_address_page_but_not_addressless_guidance():
    overview = "단지 개요 건설위치 강원특별자치도 평창군 용평면 새이들길 23 전체 세대수 미확인"
    guidance = "단지 개요와 단지주소는 원문에서 확인하세요."
    selection = pages.select([COVER, SUPPLY, RENT, NOTICE, overview, guidance])
    assert 5 in selection.pages and 6 not in selection.pages
