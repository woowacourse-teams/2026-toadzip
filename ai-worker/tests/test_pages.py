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

    assert selection == pages.PageSelection([1, 3, 4, 5, 6, 7, 9], False)


def test_selects_page_after_supply_and_rent_tables_that_may_continue():
    assert pages.select([COVER, SUPPLY + " " + RENT, QUALIFICATION, NOTICE]).pages == [1, 2, 3]


def test_skips_form_pages_even_with_keywords():
    assert pages.select([COVER, SUPPLY, "[별지1] 공급신청서 " + RENT, RENT]).pages == [1, 2, 4]


def test_skips_form_page_with_leading_page_number_footer():
    selection = pages.select([COVER, SUPPLY, RENT, NOTICE, "- 5 - [별지2] 개인정보 수집 동의서 입주자선정방법"])

    assert selection.pages == [1, 2, 3, 4]


def test_finds_rent_table_in_thousands_of_won():
    rent_in_thousands = "금회모집 임대조건 임대보증금(천원) 월임대료 (원) 67,400 3,370 64,030 342,610"

    assert pages.select([COVER, SUPPLY, rent_in_thousands]) == pages.PageSelection([1, 2, 3], False)


def test_uses_whole_document_without_supply_or_rent_table():
    assert pages.select([COVER, SUPPLY, NOTICE]) == pages.PageSelection([1, 2, 3], True)


def test_prose_words_without_values_do_not_select_page():
    prose = "당첨자 발표일이 동일한 주택 입주자선정제한 주거전용면적은 주거의 용도로만 쓰이는 면적"

    assert pages.select([COVER, SUPPLY, RENT, NOTICE, prose, "문의처는 콜센터입니다"]).pages == [1, 2, 3, 4]


def test_finds_sections_with_spaced_or_doubled_letters():
    spaced_rent = "임 대 보 증 금 월 임 대 료 2,520,000 2,394,000 50,160"
    doubled_supply = "공공공공용용용용 26.37 31.32 55.83"

    assert pages.select([COVER, NOTICE, doubled_supply, NOTICE, spaced_rent, NOTICE]).pages == [1, 3, 4, 5, 6]


def test_reads_text_of_each_page():
    with pages.open_pdf(pdf(COVER, SUPPLY, CONTACT)) as document:
        texts = pages.page_texts(document)

    assert len(texts) == 3
    assert "공용" in texts[1]


def test_prompt_numbers_selected_pages_in_order():
    with pages.open_pdf(pdf("cover", "notice", "supply", "contact")) as document:
        text = pages.prompt_pages(document, [1, 3, 4]).text

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
    with pymupdf.open(stream=pdf("cover", "rent", "rent again"), filetype="pdf") as document:
        assert pages.prompt_pages(document, [1, 2, 3]).rental_rows == 4
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
