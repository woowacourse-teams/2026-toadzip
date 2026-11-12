"""신청자격 단계에 보낼 쪽을 고르고, 사람이 읽는 순서대로 글을 만든다.

본문 단계와 쪽을 따로 고른다. 본문 단계 쪽에는 일정·접수처처럼 신청자격과 무관한 쪽이 많고, 신청자격 절에 딸린
소득·자산 기준표 쪽은 빠지기도 한다. 글도 따로 만든다. 본문 단계 글은 표를 쪽 끝에 모으고 줄을 위에서 아래로만 이어,
가로 한 장에 두 쪽을 모아 찍은 공고는 좌우 단이 문장 중간에 섞인다.
"""

import re
from dataclasses import dataclass

import pymupdf

from ai_worker import pages, tables

# 신청할 수 있는 사람을 적는 절. 영구임대·국민임대는 '입주자 선정방법'의 순위 표가 신청 가능한 사람(가~타목)을 나열한다.
_ELIGIBILITY_TITLE = re.compile(r"^(?:신청자격|입주자격|입주대상|신청대상|입주자선정|선정기준|선정방법)")
# 다른 절 이름과 함께 적힌 신청자격 절 제목('공급대상 및 신청자격'). 문장으로 이어지는 번호 항목과 가리려고 짧은 제목만 본다.
_JOINT_TITLE = re.compile(r"신청자격|입주자격")
_JOINT_TITLE_LENGTH = 20
# pages의 절 제목 낱말에 없지만 신청자격 절 바로 뒤에 자주 오는 절
_OTHER_TITLE = re.compile(r"^(?:모집일정|신청일정)")
# 고른 절 사이에 낀 쪽이 이만큼 이하면 함께 읽는다(신청자격과 선정방법 사이의 소득·자산 기준표 절).
_MAX_GAP = 3
# 사이에 낀 쪽이 제출서류 절이면 읽지 않는다. 자격 요건이 없고 서류 표가 길다.
_DOCUMENTS_TITLE = re.compile(r"^(?:신청서류|제출서류|구비서류|서류)")
# 공급표가 신청자를 「가」군·나군처럼 묶은 이름. 대학생·청년 같은 공급대상 이름은 경로 이름과 같아 따로 알려 줄 필요가 없다.
_APPLICANT_GROUP = re.compile(r"^\W*[가나다라마바사아자차카타파하]\W*군")
# 모아 찍은 쪽의 가운데 여백을 가로지르는 줄인지 볼 때 두는 여유(pt)
_GUTTER = 4


@dataclass(frozen=True)
class EligibilityPages:
    # 첨부 순서대로의 원본 쪽 번호(1부터)
    pages: list[int]
    # 첨부 순서대로의 쪽 글(읽는 순서, 표는 제자리)
    page_texts: list[str]
    # 공급표가 금액 열을 나눈 신청자 묶음 이름(가군(…), 나군(…)). 모델이 신청 경로를 공급대상에 잇는 데 쓴다.
    applicant_groups: list[str]
    # 신청자격 절 제목을 찾았는지. 못 찾으면 본문 단계가 고른 쪽을 그대로 읽는다.
    section_found: bool

    @property
    def text(self) -> str:
        """[첨부 N쪽 / 원본 M쪽] 머리글로 이은 글. 모델은 N을 근거 쪽 번호로 쓴다."""
        return "\n\n".join(f"[첨부 {index}쪽 / 원본 {number}쪽]\n{text}"
                           for index, (number, text) in enumerate(zip(self.pages, self.page_texts), 1))

    def source_texts(self, texts: list[str]) -> dict[int, str]:
        """원본 쪽 번호 → 그 쪽에서 확인할 수 있는 글. texts는 원본 전체 쪽의 글이다.
        표 칸은 좌표로 읽은 글(모델이 본 글)이 쪽 전체 글보다 정확해(쉼표·소수점 순서) 둘을 함께 둔다."""
        return {number: texts[number - 1] + "\n" + text for number, text in zip(self.pages, self.page_texts)}


def read(document: pymupdf.Document, texts: list[str], selected: pages.PromptPages, deadline: float) -> EligibilityPages:
    """texts는 원본 전체 쪽의 글, selected는 본문·공급 단계가 고른 쪽이다. deadline은 pages.page_texts와 같다."""
    numbers = section_pages(texts)
    found = bool(numbers)
    numbers = numbers or list(selected.notice_pages)
    page_texts = []
    for number in numbers:
        page = document[number - 1]
        found_tables = selected.page_tables.get(number) or tables.page_tables(page, number, None)
        page_texts.append(_reading_order_text(page, found_tables))
        pages.check_deadline(deadline)
    groups = [name for name in selected.applicant_groups() if _APPLICANT_GROUP.match(name)]
    return EligibilityPages(numbers, page_texts, groups, found)


def section_pages(texts: list[str]) -> list[int]:
    """신청자격 절이 걸친 원본 쪽 번호. 절은 제목 쪽부터 다음 절 제목이나 서식 쪽 앞까지다. 절 제목이 없으면 빈 목록이다."""
    chosen: set[int] = set()
    documents: set[int] = set()
    opened: int | None = None
    documents_from: int | None = None
    for index, text in enumerate(texts):
        if not pages.is_body_page(text):
            opened = _close(chosen, opened, index - 1)
            continue
        for title, continued in pages.numbered_headings(text, across_columns=True):
            wanted = bool(_ELIGIBILITY_TITLE.match(title)) or _is_joint_title(title)
            if not wanted and not pages.is_section_title(title) and not _OTHER_TITLE.match(title):
                continue
            # 다음 절 제목 위에 앞 절의 마지막 문단이 남아 있으면 그 쪽까지 읽는다.
            opened = _close(chosen, opened, index if wanted or continued else index - 1)
            if wanted:
                opened = index
            if documents_from is not None:
                documents.update(range(documents_from, index))
            documents_from = index if _DOCUMENTS_TITLE.match(title) else None
    _close(chosen, opened, len(texts) - 1)
    ordered = sorted(chosen)
    for before, after in zip(ordered, ordered[1:]):
        between = range(before + 1, after)
        if (0 < len(between) <= _MAX_GAP and documents.isdisjoint(between)
                and all(pages.is_body_page(texts[index]) for index in between)):
            chosen.update(between)
    return [index + 1 for index in sorted(chosen)]


def _is_joint_title(title: str) -> bool:
    return (pages.is_section_title(title) and len(title) <= _JOINT_TITLE_LENGTH and bool(_JOINT_TITLE.search(title))
            and not _DOCUMENTS_TITLE.match(title))


def _close(chosen: set[int], opened: int | None, last: int) -> None:
    """열린 절을 last 쪽까지로 닫는다. 같은 쪽에서 끝난 절도 제목 쪽은 읽는다."""
    if opened is not None:
        chosen.update(range(opened, max(last, opened) + 1))
    return None


def _reading_order_text(page: pymupdf.Page, found: tables.PageTables) -> str:
    """표 밖 줄과 본문에 딸린 표를 제자리에 둔다. 공급표는 공급 단계가 읽으므로 뺀다.
    가로 쪽에서 가운데를 가로지르는 줄이 없고 양쪽에 글이 있으면 두 쪽을 모아 찍은 것으로 보고 왼쪽 단을 먼저 읽는다."""
    boxes = [pymupdf.Rect(box) for box in found.table_boxes]
    lines: dict[tuple[int, int], list[tuple[int, float, float, float, str]]] = {}
    for word in page.get_text("words", sort=True):
        x0, y0, x1, y1, value, block, line, word_index = word[:8]
        if any(box.contains(pymupdf.Point((x0 + x1) / 2, (y0 + y1) / 2)) for box in boxes):
            continue
        lines.setdefault((block, line), []).append((word_index, x0, y0, x1, value))
    # (왼쪽, 위, 오른쪽, 글)
    items = [(min(word[1] for word in words), min(word[2] for word in words), max(word[3] for word in words),
              " ".join(word[4] for word in sorted(words)))
             for words in lines.values()]
    items += [(table.bbox[0], table.bbox[1], table.bbox[2], table.text) for table in found.notice_tables]
    middle = page.rect.width / 2
    two_up = (page.rect.width > page.rect.height
              and not any(left < middle - _GUTTER and right > middle + _GUTTER for left, _, right, _ in items)
              and any(right <= middle for _, _, right, _ in items)
              and any(left >= middle for left, _, _, _ in items))
    items.sort(key=lambda item: (two_up and (item[0] + item[2]) / 2 >= middle, round(item[1]), item[0]))
    return "\n".join(item[3] for item in items).strip()
