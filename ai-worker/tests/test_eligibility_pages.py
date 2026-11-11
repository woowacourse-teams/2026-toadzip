import re
import time

import pymupdf
import pytest

from ai_worker import eligibility_pages, pages, tables
from ai_worker.extraction import PermanentError
from conftest import grid_page

ELIGIBILITY = "2. 신청자격\n무주택세대구성원으로서 아래 요건을 모두 갖춘 자"
BODY = "가구원수별 가구당 월평균소득 기준"


@pytest.mark.parametrize("texts, expected", [
    # 절은 제목 쪽부터 다음 절 제목 앞까지다. 다음 절 제목이 쪽 맨 위에 있으면 그 쪽은 읽지 않는다.
    (["표지", ELIGIBILITY, BODY, "3. 신청방법 및 일정\n인터넷 신청"], [2, 3]),
    # 다음 절 제목 위에 앞 절의 마지막 문단이 남아 있으면 그 쪽까지 읽는다.
    ([ELIGIBILITY, "자산 보유 기준 안내\n3. 신청방법 및 일정"], [1, 2]),
    # 절 제목 낱말이 아닌 번호 목록(1. 소득 기준)은 절을 끝내지 않는다.
    ([ELIGIBILITY, "1. 소득 기준은 다음과 같습니다", "5. 유의사항"], [1, 2]),
    # 영구임대·국민임대는 신청할 수 있는 사람을 '입주자 선정 방법'의 순위 표에 적는다.
    (["표지", "4. 입주자 선정 방법\n1순위 가. 생계급여 수급자", "5. 신청서류"], [2]),
    # 신청자격과 선정방법 사이에 낀 짧은 절(소득·자산 기준표가 든 임대조건)은 함께 읽고, 길게 떨어져 있으면 읽지 않는다.
    ([ELIGIBILITY, "3. 임대조건", BODY, "4. 입주자 선정 방법", "5. 유의사항"], [1, 2, 3, 4]),
    ([ELIGIBILITY, "3. 임대조건", BODY, BODY, BODY, "4. 입주자 선정 방법", "5. 유의사항"], [1, 6]),
    # 사이에 낀 절이 제출서류 안내면 읽지 않는다. 통합공공임대는 신청자격과 선정방법 사이에 서류 표가 여러 쪽 있다.
    ([ELIGIBILITY, "3. 신청서류\n주민등록표등본", BODY, "4. 입주자 선정 방법", "5. 유의사항"], [1, 4]),
    # 제출서류 절이 한 쪽뿐이어도 읽지 않는다.
    ([ELIGIBILITY, "3. 신청서류\n주민등록표등본", "4. 입주자 선정 방법", "5. 유의사항"], [1, 3]),
    # 절 제목이 다른 낱말과 함께 적혀도('공급대상 및 신청자격') 신청자격 절이다.
    (["표지", "2. 공급대상 및 신청자격\n무주택세대구성원", BODY, "3. 신청방법 및 일정\n인터넷 신청"], [2, 3]),
    # 모아 찍은 쪽의 한 줄에 두 쪽의 절 제목이 함께 있으면 둘 다 읽는다(오른쪽 제목이 왼쪽 제목에 붙어 사라지지 않는다).
    (["2. 신청자격" + " " * 40 + "3. 신청서류\n무주택세대구성원", BODY, "4. 입주자 선정 방법", "5. 유의사항"], [1, 3]),
    # 두 쪽을 모아 찍은 공고는 오른쪽 쪽의 절 제목이 왼쪽 쪽 글과 같은 줄에 있다.
    ([ELIGIBILITY + "\n주거약자용 주택공급" + " " * 60 + "3. 신청서류", BODY, "4. 입주자 선정 방법", "5. 유의사항"], [1, 3]),
    # 서식 쪽은 절을 끝낸다.
    ([ELIGIBILITY, "[별지 제1호서식] 입주신청서", BODY], [1]),
    # 목차의 항목은 절 제목이 아니다. 신청자격 절 제목이 없으면 빈 목록이다.
    (["2. 신청자격 ·············· 3\n3. 신청방법 ·············· 5", BODY], []),
    (["표지", BODY], []),
])
def test_eligibility_section_pages_run_from_its_heading_to_the_next_section(texts, expected):
    assert eligibility_pages.section_pages(texts) == expected


def _selected(notice_pages=(1,), page_tables=None, target_values=None) -> pages.PromptPages:
    return pages.PromptPages(rental_rows=0, supply_text="", supply_pages=[], supply_row_ids=frozenset(), supply_row_text={},
                             notice_text="", notice_pages=list(notice_pages), complex_candidates=[],
                             table_structure_fallback=False, ambiguous_supply_tables=False,
                             supply_row_target_values=target_values or {}, page_tables=page_tables or {})


def _document(*page_lines, landscape=False) -> tuple[pymupdf.Document, list[str]]:
    """page_lines는 쪽마다의 (x, y, 글) 목록이다."""
    document = pymupdf.open()
    for lines in page_lines:
        page = document.new_page(width=842, height=595) if landscape else document.new_page()
        for x, y, text in lines:
            page.insert_text((x, y), text, fontname="korea", fontsize=8)
    return document, [page.get_text() for page in document]


def _read(document, texts, selected=None, deadline=None):
    return eligibility_pages.read(document, texts, selected or _selected(),
                                  time.monotonic() + 60 if deadline is None else deadline)


TWO_UP = [(40, 60, "2. 신청자격"), (40, 80, "무주택세대구성원으로서"), (460, 60, "아래 요건을 모두 갖춘 자"), (460, 80, "가. 소득 기준")]


def test_two_pages_printed_side_by_side_are_read_left_column_first():
    """가로 한 장에 두 쪽을 모아 찍은 공고는 왼쪽 쪽을 다 읽고 오른쪽 쪽을 읽는다. 위에서 아래로만 이으면 두 쪽의 문장이 줄마다 섞인다."""
    document, texts = _document(TWO_UP, landscape=True)

    assert _read(document, texts).page_texts == ["2. 신청자격\n무주택세대구성원으로서\n아래 요건을 모두 갖춘 자\n가. 소득 기준"]


def test_a_line_crossing_the_middle_or_a_portrait_page_is_read_top_to_bottom():
    crossing = [*TWO_UP, (40, 100, "가운데를 가로지르는 긴 줄 " * 6)]
    wide, wide_texts = _document(crossing, landscape=True)
    portrait, portrait_texts = _document([(x * 0.6, y, text) for x, y, text in TWO_UP])
    in_rows = "2. 신청자격\n아래 요건을 모두 갖춘 자\n무주택세대구성원으로서\n가. 소득 기준"

    assert _read(wide, wide_texts).page_texts[0].startswith(in_rows)
    assert _read(portrait, portrait_texts).page_texts == [in_rows]


def test_notice_tables_are_placed_where_they_are_and_supply_tables_are_left_out():
    """본문 단계가 찾은 표를 다시 쓴다. 소득 기준표처럼 공급표가 아닌 표는 제자리(앞뒤 문장 사이)에 두고,
    공급표 안의 글은 공급 단계가 읽으므로 뺀다. 표 안의 낱말을 표 밖 줄로 한 번 더 싣지 않는다."""
    document, texts = _document([
        (40, 60, "2. 신청자격"), (40, 80, "소득 기준은 아래 표와 같다"),
        (40, 120, "가구원수"), (200, 120, "월평균소득"), (40, 200, "자산 기준은 다음과 같다"),
        (40, 260, "주택형"), (200, 260, "모집호수"), (40, 320, "신청 제한")])
    found = tables.PageTables(
        failed=False, table_boxes=[(30, 100, 400, 140), (30, 240, 400, 280)],
        notice_tables=[tables.NoticeTable((30, 100, 400, 140), "[표 1]\n가구원수\t월평균소득")],
        supply_tables=[], complex_candidates=[], rental_keys=set(), continuation=None)

    result = _read(document, texts, _selected(page_tables={1: found}))

    assert result.page_texts == [
        "2. 신청자격\n소득 기준은 아래 표와 같다\n[표 1]\n가구원수\t월평균소득\n자산 기준은 다음과 같다\n신청 제한"]
    assert (result.pages, result.section_found) == ([1], True)
    assert result.text == "[첨부 1쪽 / 원본 1쪽]\n" + result.page_texts[0]


def test_pages_outside_the_notice_stage_get_their_tables_detected_and_a_failed_detection_keeps_the_page_text(monkeypatch):
    """본문 단계가 고르지 않은 쪽(신청자격 절에 딸린 기준표 쪽)은 표를 새로 찾는다. 표 인식에 실패해도 그 쪽 글은 그대로 보낸다."""
    document, texts = _document([(40, 60, "2. 신청자격")], [(40, 60, "총자산 3억 3,700만원 이하")])
    monkeypatch.setattr(tables, "detected_tables", lambda page: ([], True))

    result = _read(document, texts, _selected(notice_pages=[1]))

    assert (result.pages, result.page_texts) == ([1, 2], ["2. 신청자격", "총자산 3억 3,700만원 이하"])
    # 그 쪽에서 확인할 수 있는 글은 쪽 전체 글과 모델이 본 글이다. 원본 쪽 번호로 찾는다.
    assert "3억 3,700만원" in result.source_texts(texts)[2] and set(result.source_texts(texts)) == {1, 2}


def test_a_table_on_a_page_outside_the_notice_stage_is_detected_and_placed_once():
    """신청자격 절에 딸린 소득·자산 기준표 쪽은 본문 단계가 고르지 않는다. 그 쪽의 표를 새로 찾아 칸을 탭으로 나눈 표로 한 번만 싣는다."""
    document, texts = _document([(40, 60, "2. 신청자격")])
    grid_page(document, [["가구원수", "월평균소득", "비고"], ["1인", "3,482,964원", "70%"]])
    texts.append(document[1].get_text())

    result = _read(document, texts, _selected(notice_pages=[1]))

    assert result.pages == [1, 2]
    assert re.search(r"가구원수\t+월평균소득\t+비고\n1인\t+3,482,964원\t+70%", result.page_texts[1])
    assert result.page_texts[1].count("3,482,964원") == 1


def test_without_an_eligibility_heading_the_notice_stage_pages_are_read():
    document, texts = _document([(40, 60, "표지")], [(40, 60, "공급 일정")], [(40, 60, "입주 대상자는 무주택세대구성원")])

    result = _read(document, texts, _selected(notice_pages=[1, 3]))

    assert (result.pages, result.section_found) == ([1, 3], False)
    assert result.text == "[첨부 1쪽 / 원본 1쪽]\n표지\n\n[첨부 2쪽 / 원본 3쪽]\n입주 대상자는 무주택세대구성원"


def test_reading_stops_before_the_paid_call_when_the_deadline_has_passed():
    document, texts = _document([(40, 60, "2. 신청자격")])

    with pytest.raises(PermanentError):
        _read(document, texts, deadline=time.monotonic() - 1)


def test_only_applicant_group_names_of_the_supply_table_are_passed_on():
    """공급표가 신청자를 「가」군·나군으로 묶은 이름만 넘긴다. 청년·고령자 같은 공급대상 이름은 신청 경로 이름과 같아 필요 없다."""
    document, texts = _document([(40, 60, "2. 신청자격")])
    grouped = {"P1:T1:R2": {"「가」군": {}, "나군(일반)": {}}, "P1:T1:R3": {"「가」군": {}}}
    by_target = {"P1:T1:R2": {"청년": {}, "고령자": {}, "군인": {}}}

    assert _read(document, texts, _selected(target_values=grouped)).applicant_groups == ["「가」군", "나군(일반)"]
    assert _read(document, texts, _selected(target_values=by_target)).applicant_groups == []
