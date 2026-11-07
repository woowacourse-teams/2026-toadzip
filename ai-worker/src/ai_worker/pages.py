"""공고문 PDF를 열어 추출에 보낼 쪽을 고르고, 고른 쪽의 텍스트와 표를 프롬프트용 글로 만든다. 표 인식과 열 역할 판단은 tables가 한다."""

import re
import time
from dataclasses import dataclass, field

import pymupdf

from ai_worker import tables
from ai_worker.extraction import PermanentError

UNREADABLE_CODE = "PDF_UNREADABLE"
_UNREADABLE_MESSAGE = "PDF를 읽지 못했습니다. 손상되었거나 암호가 걸린 파일인지 확인해 주세요."
_EMPTY_MESSAGE = "쪽이 없는 PDF입니다."
_SLOW_CODE = "PDF_TOO_SLOW"
_SLOW_MESSAGE = "PDF 쪽을 읽는 데 시간이 너무 오래 걸려 추출하지 못했습니다."

# 굵은 제목이 "공공공용용용"처럼 같은 글자가 겹쳐 추출되는 경우를 한 글자로 줄인다.
# 숫자는 2,520,000처럼 같은 숫자가 이어지는 값이 많아 줄이지 않는다.
_REPEATED = re.compile(r"([가-힣])\1{2,}")
_WHITESPACE = re.compile(r"\s+")
# PDF에 따라 "- 27 -" 같은 쪽 번호 꼬리말이 본문보다 먼저 추출된다.
_LEADING_PAGE_NUMBER = re.compile(r"^-\d+-")
_FORM_PREFIXES = ("[별지", "[붙임", "[서식")
_HEADING = re.compile(r"(?m)^\s*\d{1,2}\.\s*([가-힣][^\n]{0,100})")
_SECTION_TITLE = re.compile(
    r"^(?:공급대상|공급계획|임대대상|임대조건|신청자격|입주자격|입주자선정|공급일정|신청방법|"
    r"신청서류|제출서류|서류|예비입주자|당첨자|계약|무주택|유의사항|기타|문의처|임대센터|단지|거주기간)"
)
_EXTRACTION_TITLE = re.compile(
    r"^(?:공급대상|공급계획|임대대상|임대조건|신청자격|입주자격|입주자선정|공급일정|신청방법|"
    r"예비입주자발표|당첨자|계약|문의처|임대센터)"
)

_AREA = re.compile(r"\d{2,3}\.\d{2,4}")
# 천원 단위 표(67,400)도 있어 콤마가 하나 이상인 금액을 센다. 임대보증금·임대료 낱말이 함께 있어야 한다.
_MONEY = re.compile(r"\d{1,3}(?:,\d{3})+")
_DATE = re.compile(r"\d{2}\.\d{1,2}\.\d{1,2}")
_PHONE = re.compile(r"\d{2,4}-\d{3,4}-\d{4}|1\d{3}-\d{4}")
_SUPPLY_TEXT_HEADER = re.compile(
    r"공급대상|공급계획|공급형|공급유형|주택형|모집(?:호수|세대수|인원|가구)|임대보증금|월임대료"
)


@dataclass(frozen=True)
class _Section:
    """추출 프롬프트가 읽어야 하는 공고문 영역. 본문 문장에도 나오는 낱말이 많아서
    표·절 제목처럼 값(면적·금액·날짜·전화번호)이 함께 있는 쪽만 인정한다.
    required인 공급표·임대조건표는 다음 쪽으로 이어질 수 있고, 둘 중 하나라도 없으면 쪽 선별을 믿지 않는다."""

    required: bool
    keywords: tuple[re.Pattern, ...]
    value: re.Pattern | None = None
    minimum: int = 0


_SECTIONS = (
    _Section(True, (re.compile("공용"),), _AREA, 3),
    _Section(True, (re.compile("임대보증금"), re.compile("임대료")), _MONEY, 2),
    _Section(False, (re.compile("공급일정|신청일정|신청기간|접수기간|신청일시"),), _DATE, 1),
    _Section(False, (re.compile("문의처|신청문의|임대문의|접수장소|접수처"),), _PHONE, 1),
    _Section(False, (re.compile(r"[가나]군\(|「[가나]」군|입주자선정방법|선정순위"),)),
)


@dataclass(frozen=True)
class _PageText:
    """PDF 한 쪽의 텍스트. 낱말은 공백을 지운 글에서, 값의 개수는 띄어쓰기를 살린 글에서 찾는다."""

    compact: str
    spaced: str

    @classmethod
    def of(cls, text: str) -> "_PageText":
        collapsed = _REPEATED.sub(r"\1", text)
        return cls(_WHITESPACE.sub("", collapsed).strip(), _WHITESPACE.sub(" ", collapsed).strip())

    def has(self, section: _Section) -> bool:
        return all(keyword.search(self.compact) for keyword in section.keywords) and (
            section.value is None or len(section.value.findall(self.spaced)) >= section.minimum
        )

    def selectable(self) -> bool:
        front = _LEADING_PAGE_NUMBER.sub("", self.compact, count=1)
        return (not front.startswith(_FORM_PREFIXES)
                and not re.match(r"[「『]?(?:공공주택특별법|주택공급에관한규칙)[^\[]*\[(?:별지|서식)", front[:160])
                and (len(re.findall(r"[·.]{5,}", self.spaced)) < 3
                     or any(self.has(section) for section in _SECTIONS if section.required)))


@dataclass(frozen=True)
class PageSelection:
    pages: list[int]  # 1부터, 오름차순
    whole_document: bool


def select(texts: list[str]) -> PageSelection:
    """첫 쪽과 영역이 있는 쪽을 고른다. 공급표나 임대조건표를 하나도 찾지 못하면 쪽 선별을 믿지 않고 전체 쪽을 쓴다."""
    pages = [_PageText.of(text) for text in texts]
    candidates = [page if page.selectable() else None for page in pages]
    headings = [_section_heading(text) if candidates[index] else None for index, text in enumerate(texts)]
    required = [section for section in _SECTIONS if section.required]
    if not all(any(page and page.has(section) for page in candidates) for section in required):
        return PageSelection(list(range(1, len(pages) + 1)), True)
    selected = {1}
    for index, page in enumerate(candidates):
        if page is None or not any(page.has(section) for section in _SECTIONS):
            continue
        selected.add(index + 1)
        # 표가 다음 쪽으로 이어지면 이어진 쪽에는 머리글이 없어 규칙에 걸리지 않는다.
        continues = any(page.has(section) for section in required)
        if continues and index + 1 < len(pages) and candidates[index + 1] is not None:
            # 제목이 반복되지 않는 연속 표도 값이 이어지는 동안 읽는다.
            for following in range(index + 1, len(pages)):
                next_page = candidates[following]
                if next_page is None or headings[following] is not None:
                    break
                if not any(
                    page.has(section) and section.value and len(section.value.findall(next_page.spaced)) >= section.minimum
                    for section in required
                ):
                    if following == index + 1:
                        selected.add(following + 1)
                    break
                selected.add(following + 1)
    # 번호가 있는 절은 다음 절 전까지 함께 읽는다. 신청자격·문의처처럼
    # 이어진 쪽에 제목이 없는 본문도 필요하다. 서식은 절 도중이어도 경계다.
    numbered = [(index, *heading) for index, heading in enumerate(headings) if heading is not None]
    for at, (start, wanted, _) in enumerate(numbered):
        if not wanted:
            continue
        boundaries = [index for index in range(start + 1, len(pages)) if candidates[index] is None]
        if at + 1 < len(numbered):
            next_page, _, continued_on_boundary = numbered[at + 1]
            boundaries.append(next_page + int(continued_on_boundary))
        end = min(boundaries) if boundaries else len(pages)
        for index in range(start, end):
            if candidates[index] is None:
                break
            selected.add(index + 1)
    return PageSelection(sorted(selected), False)


def _section_heading(text: str) -> tuple[bool, bool] | None:
    """번호 절의 추출 여부와 제목 앞에 이전 절 본문이 있는지를 읽는다. 목차 항목은 절이 아니다."""
    for match in _HEADING.finditer(_REPEATED.sub(r"\1", text)):
        if re.search(r"[·.]{5,}", match[1]):
            continue
        title = _WHITESPACE.sub("", match[1])
        if _SECTION_TITLE.match(title):
            prefix = _LEADING_PAGE_NUMBER.sub("", _PageText.of(text[:match.start()]).compact, count=1)
            return bool(_EXTRACTION_TITLE.match(title)), bool(prefix)
    return None


def open_pdf(content: bytes) -> pymupdf.Document:
    """읽을 수 없거나 쪽이 없는 PDF는 다시 해도 같으므로 PermanentError다."""
    try:
        document = pymupdf.open(stream=content, filetype="pdf")
    except (pymupdf.FileDataError, RuntimeError, ValueError) as error:
        raise PermanentError(UNREADABLE_CODE, _UNREADABLE_MESSAGE) from error
    # filetype을 줘도 PyMuPDF는 내용을 보고 HTML 등 다른 형식으로 열 수 있다.
    if not document.is_pdf or document.needs_pass:
        document.close()
        raise PermanentError(UNREADABLE_CODE, _UNREADABLE_MESSAGE)
    if document.page_count == 0:
        document.close()
        raise PermanentError(UNREADABLE_CODE, _EMPTY_MESSAGE)
    return document


def page_texts(document: pymupdf.Document, deadline: float) -> list[str]:
    """deadline(time.monotonic() 기준)은 모델을 부를 시간이 남는 마지막 시각이다. 쪽마다 확인해 큰 PDF가 시도 예산을 다 쓰기 전에 멈춘다."""
    texts = []
    for page in document:
        # 제목보다 먼저 저장된 표·꼬리말이 있어도 시각적 읽기 순서로 절을 찾는다.
        texts.append(page.get_text("text", sort=True))
        _check_deadline(deadline)
    return texts


def _check_deadline(deadline: float) -> None:
    """같은 PDF는 다시 해도 느리므로 PermanentError다."""
    if time.monotonic() > deadline:
        raise PermanentError(_SLOW_CODE, _SLOW_MESSAGE)


@dataclass(frozen=True)
class PromptPages:
    # 임대조건표에서 센 서로 다른 (주택형, 공급계층) 줄 수. 결과의 공급대상 수는 이보다 적을 수 없다.
    rental_rows: int
    # 공급·임대 표 전용 입력. 행 ID는 원본 쪽·표·행에서 결정한다.
    supply_text: str
    supply_pages: list[int]
    supply_row_ids: frozenset[str]
    supply_row_text: dict[str, str]
    # 표 셀을 제거한 본문과 비공급 표. 일정·접수 정보가 공급표 단계에서 빠지지 않게 분리한다.
    notice_text: str
    notice_pages: list[int]
    complex_candidates: list[str]
    table_structure_fallback: bool
    ambiguous_supply_tables: bool
    # 표 머리글에서 바로 확인한 필드별 원문 셀. 숫자 추출값은 같은 의미의 열과 대조한다.
    supply_row_values: dict[str, dict[str, str]] = field(default_factory=dict)
    # 표 행을 만들지 못했지만 공급표로 보이는 쪽. 이 상태는 성공 결과로 조용히 내보내지 않는다.
    unstructured_supply_pages: list[int] = field(default_factory=list)
    # 공급표의 셀 구조. 병합 칸(None)과 빈칸('')을 구분해 코드가 공급행을 묶는다.
    supply_tables: list[tables.SupplyTable] = field(default_factory=list)
    # 표를 찾지 못했지만 공급표 같은 본문이 있는 쪽. 선이 없는 표의 행이 빠졌을 수 있다.
    tableless_supply_pages: list[int] = field(default_factory=list)


def prompt_pages(document: pymupdf.Document, pages: list[int], deadline: float) -> PromptPages:
    """고른 쪽을 [첨부 N쪽] 순서로 잇는다. 모델은 이 N을 근거 쪽 번호로 쓰고, 결과에서 원본 쪽 번호로 되돌린다.
    deadline은 page_texts와 같다. 표 찾기가 쪽마다 1초 안팎이라 쪽마다 확인한다."""
    notice_parts, supply_parts = [], []
    rental, supply_row_ids, supply_row_text, supply_row_values, candidates = set(), set(), {}, {}, []
    notice_pages, supply_pages, fallback_pages, unstructured_supply_pages = [], [], [], []
    supply_tables, tableless_supply_pages = [], []
    previous_supply: tables.SupplyContinuation | None = None
    ambiguous_table = False

    for number in pages:
        page = document[number - 1]
        text = page.get_text("text")
        found = tables.page_tables(page, number, previous_supply)
        if found.failed:
            fallback_pages.append(number)
            notice_body = text
            if _has_supply_table_text(text):
                unstructured_supply_pages.append(number)
        else:
            notice_body = _text_without_tables(page, found.table_boxes)
            if not found.table_boxes and _has_supply_table_text(text):
                tableless_supply_pages.append(number)

        notice_content = "\n".join([notice_body, *found.notice_rendered]).strip()
        if notice_content:
            notice_pages.append(number)
            notice_parts.append(f"[첨부 {len(notice_pages)}쪽 / 원본 {number}쪽]\n{notice_content}")

        ambiguous_table = ambiguous_table or any(table.ambiguous for table in found.supply_tables)
        if found.supply_tables:
            if number not in supply_pages:
                supply_pages.append(number)
                supply_parts.append(f"[첨부 {len(supply_pages)}쪽 / 원본 {number}쪽]")
            for table in found.supply_tables:
                supply_parts.append(f"[표 {table.number} / 원본 {number}쪽]")
                supply_tables.append(table)
                for row in table.rows:
                    supply_row_ids.add(row.id)
                    supply_row_text[row.id] = row.text
                    supply_row_values[row.id] = row.values
                    supply_parts.append(f"[{row.id}] {row.text}")
            candidates.extend(found.complex_candidates)
        previous_supply = found.continuation
        rental |= found.rental_keys
        _check_deadline(deadline)

    fallback = bool(fallback_pages)
    if not supply_row_ids:
        # 표 셀 ID가 없으면 선별 쪽 원문도 보존한다. 공급표처럼 보이는 쪽은 호출자가 모델 실행 전에 실패시킨다.
        fallback = True
        fallback_pages = list(dict.fromkeys([*fallback_pages, *pages]))
        for number in pages:
            if _has_supply_table_text(document[number - 1].get_text("text")):
                unstructured_supply_pages.append(number)
    fallback_text = []
    for number in fallback_pages:
        if number not in supply_pages:
            supply_pages.append(number)
        source = document[number - 1].get_text("text")
        fallback_text.append(f"[첨부 {supply_pages.index(number) + 1}쪽 / 원본 {number}쪽]\n{source}")
    supply_parts.extend(fallback_text)

    return PromptPages(
        rental_rows=len(rental),
        supply_text="\n\n".join(supply_parts),
        supply_pages=supply_pages,
        supply_row_ids=frozenset(supply_row_ids),
        supply_row_text=supply_row_text,
        notice_text="\n\n".join(notice_parts),
        notice_pages=notice_pages,
        complex_candidates=list(dict.fromkeys(candidates)),
        table_structure_fallback=fallback,
        ambiguous_supply_tables=ambiguous_table,
        supply_row_values=supply_row_values,
        unstructured_supply_pages=list(dict.fromkeys(unstructured_supply_pages)),
        supply_tables=supply_tables,
        tableless_supply_pages=tableless_supply_pages,
    )


def _has_supply_table_text(text: str) -> bool:
    """구조화하지 못한 원문에 공급·임대 머리글과 표 수준의 값이 함께 있는지 확인한다."""
    normalized = _REPEATED.sub(r"\1", text)
    if not _SUPPLY_TEXT_HEADER.search(_WHITESPACE.sub("", normalized)):
        return False
    numeric_values = re.findall(r"\d{1,3}(?:,\d{3})*(?:\.\d+)?", normalized)
    return len(_AREA.findall(normalized)) >= 2 or len(numeric_values) >= 4


def _text_without_tables(page: pymupdf.Page, boxes: list[tuple[float, float, float, float]]) -> str:
    """표 경계 안 단어를 빼고 본문만 돌려준다. 좌표가 없는 표 텍스트를 다른 단계에 중복 전송하지 않는다."""
    if not boxes:
        return page.get_text("text", sort=True)
    rectangles = [pymupdf.Rect(box) for box in boxes]
    lines: dict[tuple[int, int], list[tuple[int, float, float, str]]] = {}
    for word in page.get_text("words", sort=True):
        x0, y0, x1, y1, value, block, line, word_index = word[:8]
        center = pymupdf.Point((x0 + x1) / 2, (y0 + y1) / 2)
        if any(rectangle.contains(center) for rectangle in rectangles):
            continue
        lines.setdefault((block, line), []).append((word_index, y0, x0, value))
    ordered = sorted(lines.values(), key=lambda words: (min(word[1] for word in words), min(word[2] for word in words)))
    return "\n".join(" ".join(word[3] for word in sorted(line, key=lambda word: word[0])) for line in ordered).strip()
