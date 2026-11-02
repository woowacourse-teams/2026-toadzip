"""공고문 PDF를 열어 추출에 보낼 쪽을 고르고, 고른 쪽의 텍스트와 표를 프롬프트용 글로 만든다."""

import logging
import re
from dataclasses import dataclass

import pymupdf

from ai_worker.extraction import PermanentError

log = logging.getLogger(__name__)

UNREADABLE_CODE = "PDF_UNREADABLE"
UNREADABLE_MESSAGE = "PDF를 읽지 못했습니다. 손상되었거나 암호가 걸린 파일인지 확인해 주세요."
EMPTY_MESSAGE = "쪽이 없는 PDF입니다."

# 굵은 제목이 "공공공용용용"처럼 같은 글자가 겹쳐 추출되는 경우를 한 글자로 줄인다.
# 숫자는 2,520,000처럼 같은 숫자가 이어지는 값이 많아 줄이지 않는다.
REPEATED = re.compile(r"([가-힣])\1{2,}")
WHITESPACE = re.compile(r"\s+")
# PDF에 따라 "- 27 -" 같은 쪽 번호 꼬리말이 본문보다 먼저 추출된다.
LEADING_PAGE_NUMBER = re.compile(r"^-\d+-")
FORM_PREFIX = "[별지"

AREA = re.compile(r"\d{2,3}\.\d{2,4}")
# 천원 단위 표(67,400)도 있어 콤마가 하나 이상인 금액을 센다. 임대보증금·임대료 낱말이 함께 있어야 한다.
MONEY = re.compile(r"\d{1,3}(?:,\d{3})+")
DATE = re.compile(r"\d{2}\.\d{1,2}\.\d{1,2}")
PHONE = re.compile(r"\d{2,4}-\d{3,4}-\d{4}|1\d{3}-\d{4}")


@dataclass(frozen=True)
class Section:
    """추출 프롬프트가 읽어야 하는 공고문 영역. 본문 문장에도 나오는 낱말이 많아서
    표·절 제목처럼 값(면적·금액·날짜·전화번호)이 함께 있는 쪽만 인정한다.
    required인 공급표·임대조건표는 다음 쪽으로 이어질 수 있고, 둘 중 하나라도 없으면 쪽 선별을 믿지 않는다."""

    required: bool
    keywords: tuple[re.Pattern, ...]
    value: re.Pattern | None = None
    minimum: int = 0


SECTIONS = (
    Section(True, (re.compile("공용"),), AREA, 3),
    Section(True, (re.compile("임대보증금"), re.compile("임대료")), MONEY, 2),
    Section(False, (re.compile("공급일정|신청일정|신청기간|접수기간|신청일시"),), DATE, 1),
    Section(False, (re.compile("문의처|신청문의|임대문의|접수장소|접수처"),), PHONE, 1),
    Section(False, (re.compile(r"[가나]군\(|「[가나]」군|입주자선정방법|선정순위"),)),
)


@dataclass(frozen=True)
class PageText:
    """PDF 한 쪽의 텍스트. 낱말은 공백을 지운 글에서, 값의 개수는 띄어쓰기를 살린 글에서 찾는다."""

    compact: str
    spaced: str

    @classmethod
    def of(cls, text: str) -> "PageText":
        collapsed = REPEATED.sub(r"\1", text)
        return cls(WHITESPACE.sub("", collapsed).strip(), WHITESPACE.sub(" ", collapsed).strip())

    def has(self, section: Section) -> bool:
        return all(keyword.search(self.compact) for keyword in section.keywords) and (
            section.value is None or len(section.value.findall(self.spaced)) >= section.minimum
        )

    def selectable(self) -> bool:
        return not LEADING_PAGE_NUMBER.sub("", self.compact, count=1).startswith(FORM_PREFIX)


@dataclass(frozen=True)
class PageSelection:
    pages: list[int]  # 1부터, 오름차순
    whole_document: bool


def select(texts: list[str]) -> PageSelection:
    """첫 쪽과 영역이 있는 쪽을 고른다. 공급표나 임대조건표를 하나도 찾지 못하면 쪽 선별을 믿지 않고 전체 쪽을 쓴다."""
    pages = [PageText.of(text) for text in texts]
    candidates = [page if page.selectable() else None for page in pages]
    required = [section for section in SECTIONS if section.required]
    if not all(any(page and page.has(section) for page in candidates) for section in required):
        return PageSelection(list(range(1, len(pages) + 1)), True)
    selected = {1}
    for index, page in enumerate(candidates):
        if page is None or not any(page.has(section) for section in SECTIONS):
            continue
        selected.add(index + 1)
        # 표가 다음 쪽으로 이어지면 이어진 쪽에는 머리글이 없어 규칙에 걸리지 않는다.
        continues = any(page.has(section) for section in required)
        if continues and index + 1 < len(pages) and candidates[index + 1] is not None:
            selected.add(index + 2)
    return PageSelection(sorted(selected), False)


def open_pdf(content: bytes) -> pymupdf.Document:
    """읽을 수 없거나 쪽이 없는 PDF는 다시 해도 같으므로 PermanentError다."""
    try:
        document = pymupdf.open(stream=content, filetype="pdf")
    except (pymupdf.FileDataError, RuntimeError, ValueError) as error:
        raise PermanentError(UNREADABLE_CODE, UNREADABLE_MESSAGE) from error
    # filetype을 줘도 PyMuPDF는 내용을 보고 HTML 등 다른 형식으로 열 수 있다.
    if not document.is_pdf or document.needs_pass:
        document.close()
        raise PermanentError(UNREADABLE_CODE, UNREADABLE_MESSAGE)
    if document.page_count == 0:
        document.close()
        raise PermanentError(UNREADABLE_CODE, EMPTY_MESSAGE)
    return document


def page_texts(document: pymupdf.Document) -> list[str]:
    return [page.get_text("text") for page in document]


def prompt_pages(document: pymupdf.Document, pages: list[int]) -> str:
    """고른 쪽을 [첨부 N쪽] 순서로 잇는다. 모델은 이 N을 근거 쪽 번호로 쓰고, 결과에서 원본 쪽 번호로 되돌린다."""
    return "\n\n".join(f"[첨부 {index}쪽]\n{page_with_tables(document[number - 1])}"
                       for index, number in enumerate(pages, 1))


def page_with_tables(page: pymupdf.Page) -> str:
    """쪽 텍스트와 PyMuPDF가 찾은 표(마크다운)를 함께 준다. 표 셀 병합은 마크다운에서 빈 칸으로 보인다."""
    parts = [page.get_text("text")]
    try:
        parts += [f"[표 {index}]\n{table.to_markdown()}" for index, table in enumerate(page.find_tables().tables, 1)]
    except Exception:
        # 표 인식 실패는 그 쪽 텍스트만으로 진행한다. 평가는 실패 문구를 프롬프트에 붙였지만 예외 내용은 모델에 보내지 않는다.
        log.warning("%d쪽 표 인식에 실패해 텍스트만 보냅니다.", page.number + 1, exc_info=True)
        return parts[0]
    return "\n".join(parts)
