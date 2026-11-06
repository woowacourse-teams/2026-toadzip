"""공고문 PDF를 열어 추출에 보낼 쪽을 고르고, 고른 쪽의 텍스트와 표를 프롬프트용 글로 만든다."""

import logging
import re
import time
from dataclasses import dataclass, field

import pymupdf

from ai_worker.extraction import PermanentError

log = logging.getLogger(__name__)

UNREADABLE_CODE = "PDF_UNREADABLE"
UNREADABLE_MESSAGE = "PDF를 읽지 못했습니다. 손상되었거나 암호가 걸린 파일인지 확인해 주세요."
EMPTY_MESSAGE = "쪽이 없는 PDF입니다."
SLOW_CODE = "PDF_TOO_SLOW"
SLOW_MESSAGE = "PDF 쪽을 읽는 데 시간이 너무 오래 걸려 추출하지 못했습니다."

# 굵은 제목이 "공공공용용용"처럼 같은 글자가 겹쳐 추출되는 경우를 한 글자로 줄인다.
# 숫자는 2,520,000처럼 같은 숫자가 이어지는 값이 많아 줄이지 않는다.
REPEATED = re.compile(r"([가-힣])\1{2,}")
WHITESPACE = re.compile(r"\s+")
# PDF에 따라 "- 27 -" 같은 쪽 번호 꼬리말이 본문보다 먼저 추출된다.
LEADING_PAGE_NUMBER = re.compile(r"^-\d+-")
FORM_PREFIXES = ("[별지", "[붙임", "[서식")
HEADING = re.compile(r"(?m)^\s*\d{1,2}\.\s*([가-힣][^\n]{0,100})")
SECTION_TITLE = re.compile(
    r"^(?:공급대상|공급계획|임대대상|임대조건|신청자격|입주자격|입주자선정|공급일정|신청방법|"
    r"신청서류|제출서류|서류|예비입주자|당첨자|계약|무주택|유의사항|기타|문의처|임대센터|단지|거주기간)"
)
EXTRACTION_TITLE = re.compile(
    r"^(?:공급대상|공급계획|임대대상|임대조건|신청자격|입주자격|입주자선정|공급일정|신청방법|"
    r"예비입주자발표|당첨자|계약|문의처|임대센터)"
)

AREA = re.compile(r"\d{2,3}\.\d{2,4}")
# 천원 단위 표(67,400)도 있어 콤마가 하나 이상인 금액을 센다. 임대보증금·임대료 낱말이 함께 있어야 한다.
MONEY = re.compile(r"\d{1,3}(?:,\d{3})+")
DATE = re.compile(r"\d{2}\.\d{1,2}\.\d{1,2}")
# 표 칸 글자는 콤마가 줄바꿈 뒤로 밀려 "41854000\n, ,"처럼 나와 숫자·콤마·공백만으로 된 칸을 금액으로 본다.
AMOUNT_CELL = re.compile(r"[\d,\s]+")
DIGIT = re.compile(r"\d")
RENT_HEADER = re.compile(r"임대조건|월임대료")
PHONE = re.compile(r"\d{2,4}-\d{3,4}-\d{4}|1\d{3}-\d{4}")
SUPPLY_TABLE_HEADER = re.compile(r"공급형|공급유형|주택형|모집\s*(?:호수|세대수|인원|가구)|임대보증금|월\s*임대료")
AREA_CELL = re.compile(r"\d{2,3}\.\d{2,4}")
MONEY_CELL = re.compile(r"\d{1,3}(?:,\d{3})+")
TABLE_HEADER = re.compile(r"단지명|공급형|주택형|공급대상|공급계층|청약구분|전용|임대조건|임대보증금|월임대료")
TABLE_HEADER_CELL = re.compile(
    r"단지명|공급형|주택형|주택구분|공급대상|공급계층|청약구분|모집(?:호수|세대수|인원|가구)|"
    r"일반공급|우선공급|예비자|당첨자|전용|공용|계약면적|건설(?:호수|세대수)|총세대수|"
    r"주차(?:장|대수|호수)|층수|난방|거주기간|합계|임대조건|임대보증금|월임대료"
)
SUPPLY_TEXT_HEADER = re.compile(
    r"공급대상|공급계획|공급형|공급유형|주택형|모집(?:호수|세대수|인원|가구)|임대보증금|월임대료"
)


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
        front = LEADING_PAGE_NUMBER.sub("", self.compact, count=1)
        return (not front.startswith(FORM_PREFIXES)
                and not re.match(r"[「『]?(?:공공주택특별법|주택공급에관한규칙)[^\[]*\[(?:별지|서식)", front[:160])
                and (len(re.findall(r"[·.]{5,}", self.spaced)) < 3
                     or any(self.has(section) for section in SECTIONS if section.required)))


@dataclass(frozen=True)
class PageSelection:
    pages: list[int]  # 1부터, 오름차순
    whole_document: bool


def select(texts: list[str]) -> PageSelection:
    """첫 쪽과 영역이 있는 쪽을 고른다. 공급표나 임대조건표를 하나도 찾지 못하면 쪽 선별을 믿지 않고 전체 쪽을 쓴다."""
    pages = [PageText.of(text) for text in texts]
    candidates = [page if page.selectable() else None for page in pages]
    headings = [section_heading(text) if candidates[index] else None for index, text in enumerate(texts)]
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


def section_heading(text: str) -> tuple[bool, bool] | None:
    """번호 절의 추출 여부와 제목 앞에 이전 절 본문이 있는지를 읽는다. 목차 항목은 절이 아니다."""
    for match in HEADING.finditer(REPEATED.sub(r"\1", text)):
        if re.search(r"[·.]{5,}", match[1]):
            continue
        title = WHITESPACE.sub("", match[1])
        if SECTION_TITLE.match(title):
            prefix = LEADING_PAGE_NUMBER.sub("", PageText.of(text[:match.start()]).compact, count=1)
            return bool(EXTRACTION_TITLE.match(title)), bool(prefix)
    return None


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


def page_texts(document: pymupdf.Document, deadline: float) -> list[str]:
    """deadline(time.monotonic() 기준)은 모델을 부를 시간이 남는 마지막 시각이다. 쪽마다 확인해 큰 PDF가 시도 예산을 다 쓰기 전에 멈춘다."""
    texts = []
    for page in document:
        # 제목보다 먼저 저장된 표·꼬리말이 있어도 시각적 읽기 순서로 절을 찾는다.
        texts.append(page.get_text("text", sort=True))
        check_deadline(deadline)
    return texts


def check_deadline(deadline: float) -> None:
    """같은 PDF는 다시 해도 느리므로 PermanentError다."""
    if time.monotonic() > deadline:
        raise PermanentError(SLOW_CODE, SLOW_MESSAGE)


@dataclass(frozen=True)
class PromptPages:
    # 이전 호출자가 쓰는 전체 원문. 화면용 추출 프롬프트 테스트와 진단에서 사용한다.
    text: str
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
    supply_tables: list["SupplyTable"] = field(default_factory=list)
    # 표를 찾지 못했지만 공급표 같은 본문이 있는 쪽. 선이 없는 표의 행이 빠졌을 수 있다.
    tableless_supply_pages: list[int] = field(default_factory=list)


@dataclass(frozen=True)
class SupplyTable:
    page: int
    number: int
    cells: list[list[str | None]]
    # 머리글이 없는 이어짐 표는 앞 공급표의 열 역할을 물려받는다.
    columns: dict[str, int]
    ambiguous: bool


@dataclass(frozen=True)
class DetectedTable:
    cells: list[list[str | None]]
    bbox: tuple[float, float, float, float]
    supply: bool = False
    ambiguous: bool = False


def prompt_pages(document: pymupdf.Document, pages: list[int], deadline: float) -> PromptPages:
    """고른 쪽을 [첨부 N쪽] 순서로 잇는다. 모델은 이 N을 근거 쪽 번호로 쓰고, 결과에서 원본 쪽 번호로 되돌린다.
    deadline은 page_texts와 같다. 표 찾기가 쪽마다 1초 안팎이라 쪽마다 확인한다."""
    all_parts, notice_parts, supply_parts = [], [], []
    rental, supply_row_ids, supply_row_text, supply_row_values, candidates = set(), set(), {}, {}, []
    notice_pages, supply_pages, fallback_pages, unstructured_supply_pages = [], [], [], []
    supply_tables, tableless_supply_pages = [], []
    previous_supply: tuple[int, int, dict[str, int]] | None = None
    ambiguous_table = False

    for attachment_page, number in enumerate(pages, 1):
        page = document[number - 1]
        text = page.get_text("text")
        tables, failed = detected_tables(page)
        if failed:
            fallback_pages.append(number)
            notice_body = text
            if has_supply_table_text(text):
                unstructured_supply_pages.append(number)
        else:
            tables = mark_supply_tables(tables, number, previous_supply)
            notice_body = text_without_tables(page, [table.bbox for table in tables])
            if not tables and has_supply_table_text(text):
                tableless_supply_pages.append(number)

        rendered_tables = [render_table(table.cells, index) for index, table in enumerate(tables, 1)]
        combined = "\n".join([text, *rendered_tables])
        all_parts.append(f"[첨부 {attachment_page}쪽]\n{combined}")

        non_supply_rendered = [render_table(table.cells, index) for index, table in enumerate(tables, 1)
                               if not table.supply or table.ambiguous]
        notice_content = "\n".join([notice_body, *non_supply_rendered]).strip()
        if notice_content:
            notice_pages.append(number)
            notice_parts.append(f"[첨부 {len(notice_pages)}쪽 / 원본 {number}쪽]\n{notice_content}")

        page_supply_tables = [table for table in tables if table.supply]
        ambiguous_table = ambiguous_table or any(table.ambiguous for table in page_supply_tables)
        if page_supply_tables:
            previous_columns = previous_supply[2] if previous_supply and previous_supply[0] + 1 == number else {}
            last_columns = previous_columns
            if number not in supply_pages:
                supply_pages.append(number)
                supply_parts.append(f"[첨부 {len(supply_pages)}쪽 / 원본 {number}쪽]")
            for table_index, table in enumerate(tables, 1):
                if not table.supply:
                    continue
                table_columns = table_field_columns(table.cells)
                if table_columns:
                    last_columns = table_columns
                else:
                    table_columns = last_columns
                supply_parts.append(f"[표 {table_index} / 원본 {number}쪽]")
                supply_tables.append(SupplyTable(number, table_index, table.cells, table_columns, table.ambiguous))
                for row_index, row in enumerate(table.cells, 1):
                    row_id = source_row_id(number, table_index, row_index)
                    supply_row_ids.add(row_id)
                    row_text = "\t".join(cell or "" for cell in row)
                    supply_row_text[row_id] = row_text
                    supply_row_values[row_id] = table_row_field_values(table.cells, row_index - 1, table_columns)
                    supply_parts.append(f"[{row_id}] {row_text}")
                candidates.extend(complex_name_candidates(table.cells))
            previous_supply = (
                number,
                max(len(table.cells[0]) if table.cells else 0 for table in page_supply_tables),
                last_columns,
            )
        else:
            previous_supply = None

        for table in tables:
            rental |= rental_keys(table.cells)
        check_deadline(deadline)

    fallback = bool(fallback_pages)
    if not supply_row_ids:
        # 표 셀 ID가 없으면 선별 쪽 원문도 보존한다. 공급표처럼 보이는 쪽은 호출자가 모델 실행 전에 실패시킨다.
        fallback = True
        fallback_pages = list(dict.fromkeys([*fallback_pages, *pages]))
        for number in pages:
            if has_supply_table_text(document[number - 1].get_text("text")):
                unstructured_supply_pages.append(number)
    fallback_text = []
    for number in fallback_pages:
        if number not in supply_pages:
            supply_pages.append(number)
        source = document[number - 1].get_text("text")
        fallback_text.append(f"[첨부 {supply_pages.index(number) + 1}쪽 / 원본 {number}쪽]\n{source}")
    supply_parts.extend(fallback_text)

    return PromptPages(
        text="\n\n".join(all_parts),
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


def has_supply_table_text(text: str) -> bool:
    """구조화하지 못한 원문에 공급·임대 머리글과 표 수준의 값이 함께 있는지 확인한다."""
    normalized = REPEATED.sub(r"\1", text)
    if not SUPPLY_TEXT_HEADER.search(WHITESPACE.sub("", normalized)):
        return False
    numeric_values = re.findall(r"\d{1,3}(?:,\d{3})*(?:\.\d+)?", normalized)
    return len(AREA_CELL.findall(normalized)) >= 2 or len(numeric_values) >= 4


def table_field_columns(rows: list[list[str | None]], row_index: int | None = None) -> dict[str, int]:
    """표 머리글 행을 열 위치로 바꾼다. 여러 열이 같은 뜻이면 그 필드는 일부러 매핑하지 않는다."""
    if not rows:
        return {}
    column_count = max((len(row) for row in rows), default=0)
    header_rows = rows[:min(row_index if row_index is not None else 6, 6)]
    fields = {
        "complexName": lambda header: bool(re.search(r"단지명", header)),
        "housingType": lambda header: bool(re.search(r"청약구분|주택형|공급유형", header)),
        "target": lambda header: bool(re.search(r"공급대상|공급계층|청약대상", header)),
        "reserveCount": lambda header: bool(re.search(r"예비(?:입주자)?", header))
            and not re.search(r"잔여|기존|현재|대기", header),
        "supplyHouseholdCount": lambda header: bool(re.search(r"당첨(?:자|인원|호수)", header)),
        "totalSupplyHouseholdCount": lambda header: bool(
            re.search(r"모집\s*(?:호수|인원|세대수)", header)
            and not re.search(r"예비|당첨|우선|일반", header)
        ),
        "exclusiveArea": lambda header: bool(re.search(r"주거전용|전용면적", header)),
        "commonArea": lambda header: bool(re.search(r"주거공용", header)),
        "supplyArea": lambda header: bool(re.search(r"공급면적", header)),
        "constructedHouseholdCount": lambda header: bool(re.search(r"건설(?:호수|세대수)|주택형별세대수", header)),
        "totalHouseholdCount": lambda header: bool(re.search(r"총세대수|전체세대수", header)),
        "parkingCount": lambda header: bool(re.search(r"주차대수|주차호수", header)),
        "rentalDeposit": lambda header: bool(
            re.search(r"임대보증금", header) and not re.search(r"전환|증액|최소|최대", header)
        ),
        "monthlyRent": lambda header: bool(
            re.search(r"월임대료", header) and not re.search(r"전환|증액|최소|최대", header)
        ),
        "convertedDeposit": lambda header: bool(
            not re.search(r"월임대료", header)
            and re.search(r"(?:전환|증액|최대).*보증금|보증금.*(?:전환|증액|최대)", header)
        ),
    }
    columns: dict[str, int] = {}
    normalized_headers = table_column_headers(header_rows, column_count)
    for name, matches in fields.items():
        indexes = []
        for column in range(column_count):
            header = normalized_headers[column]
            if header and matches(header):
                indexes.append(column)
        if len(indexes) == 1:
            columns[name] = indexes[0]
    detail = target_detail_column(header_rows, columns)
    if detail is not None:
        columns["targetDetail"] = detail
    return columns


def target_detail_column(header_rows: list[list[str | None]], columns: dict[str, int]) -> int | None:
    """공급대상 머리글이 오른쪽 칸까지 병합돼 있으면 그 칸은 대상의 세부 구분(소득 있음·없음)이다."""
    target = columns.get("target")
    if target is None or target + 1 in columns.values():
        return None
    for row in header_rows:
        if target + 1 < len(row) and row[target] and re.search(r"공급대상|공급계층|청약대상", compact(row[target])):
            return target + 1 if row[target + 1] is None else None
    return None


def table_column_headers(rows: list[list[str | None]], column_count: int) -> list[str]:
    """표 머리글의 병합된 상위 셀을 오른쪽 하위 열에 이어 붙인다."""
    contexts = [""] * column_count
    parts = [[] for _ in range(column_count)]
    for row in rows:
        cells = [(cell or "").strip() for cell in row]
        for column, cell in enumerate(cells[:column_count]):
            if cell and re.search(r"최소|최대|전환|증액", cell):
                contexts[column] = cell
                following = column + 1
                while following < column_count and (following >= len(cells) or not cells[following]):
                    contexts[following] = cell
                    following += 1
        for column in range(column_count):
            if contexts[column]:
                parts[column].append(contexts[column])
            if column < len(cells) and cells[column]:
                parts[column].append(cells[column])
    return [re.sub(r"\s+", "", "".join(header)) for header in parts]


def table_row_field_values(rows: list[list[str | None]], row_index: int,
                           inherited_columns: dict[str, int] | None = None) -> dict[str, str]:
    """표 머리글 행을 열 위치로 연결해, 모델이 낸 수치가 같은 의미의 원문 칸에 있는지 검증한다."""
    if not rows or row_index < 0 or row_index >= len(rows):
        return {}
    row = rows[row_index]
    nonempty = [cell for cell in row if cell and cell.strip()]
    if nonempty and all(TABLE_HEADER_CELL.search(compact(cell)) for cell in nonempty):
        return {}
    columns = table_field_columns(rows, row_index)
    for name, column in (inherited_columns or {}).items():
        columns.setdefault(name, column)
    values = {name: row[column] for name, column in columns.items() if column < len(row) and row[column]}
    headers = table_column_headers(rows[:min(row_index, 6)], max(map(len, rows)))
    for field in ("rentalDeposit", "monthlyRent", "convertedDeposit"):
        column = columns.get(field)
        if column is not None and field in values:
            unit = re.search(r"만원|천원|원", headers[column])
            if unit:
                values[f"{field}Unit"] = unit.group()
    return values


def page_with_tables(page: pymupdf.Page) -> tuple[str, list[list[list[str | None]]]]:
    """쪽 텍스트와 PyMuPDF가 찾은 표(탭 구분)를 함께 준다. 표 셀 병합은 빈 칸으로 보인다.
    검산에 쓰도록 표 칸도 돌려준다. 표 찾기는 쪽마다 1초 안팎 걸려 한 번만 한다."""
    text = page.get_text("text")
    tables, failed = detected_tables(page)
    if failed:
        return text, []
    return "\n".join([text, *(render_table(table.cells, index) for index, table in enumerate(tables, 1))]), [
        table.cells for table in tables
    ]


def detected_tables(page: pymupdf.Page) -> tuple[list[DetectedTable], bool]:
    """표 원문·좌표를 한 번만 읽는다. 하나라도 칸을 읽지 못하면 페이지 전체를 구조화 실패로 되돌린다."""
    try:
        tables = [DetectedTable(table_cells(page, table), tuple(getattr(table, "bbox", page.rect)))
                  for table in page.find_tables().tables]
    except Exception:
        log.warning("%d쪽 표 인식에 실패해 텍스트만 보냅니다.", page.number + 1, exc_info=True)
        return [], True
    return tables, False


def mark_supply_tables(tables: list[DetectedTable], page_number: int,
                       previous_supply: tuple[int, int, dict[str, int]] | None) -> list[DetectedTable]:
    """표 머리글을 우선 보고, 바로 앞 공급표와 열 수가 같은 머리글 없는 이어짐도 공급표로 분류한다."""
    marked = []
    for table in tables:
        columns = max((len(row) for row in table.cells), default=0)
        head = " ".join(compact(cell) for row in table.cells[:4] for cell in row)
        explicit = bool(SUPPLY_TABLE_HEADER.search(head)) and columns >= 3
        area_values = sum(len(AREA_CELL.findall(cell or "")) for row in table.cells for cell in row)
        money_values = sum(len(MONEY_CELL.findall(cell or "")) for row in table.cells for cell in row)
        continued = (
            previous_supply is not None
            and previous_supply[0] + 1 == page_number
            and previous_supply[1] == columns
            and (area_values >= 2 or money_values >= 3)
        )
        wide_data = columns >= 5 and (area_values >= 2 or money_values >= 3)
        supply = explicit or continued or wide_data
        marked.append(DetectedTable(table.cells, table.bbox, supply, wide_data and not explicit and not continued))
    return marked


def text_without_tables(page: pymupdf.Page, boxes: list[tuple[float, float, float, float]]) -> str:
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


def render_table(cells: list[list[str | None]], index: int) -> str:
    return f"[표 {index}]\n" + "\n".join("\t".join(cell or "" for cell in row) for row in cells)


def source_row_id(page_number: int, table_number: int, row_number: int) -> str:
    return f"P{page_number}:T{table_number}:R{row_number}"


def complex_name_candidates(cells: list[list[str | None]]) -> list[str]:
    candidates = []
    for row in cells:
        name = (row[0] or "").strip() if row else ""
        if (not name or TABLE_HEADER.search(compact(name)) or compact(name).isdigit()
                or AREA_CELL.search(name)):
            continue
        if not any(compact(name) == compact(candidate) for candidate in candidates):
            candidates.append(name)
    return candidates


def table_cells(page: pymupdf.Page, table) -> list[list[str | None]]:
    """표 인식기의 글자 순서에서 소수점·쉼표가 뒤로 밀려 각 셀의 원문을 좌표로 읽는다.
    병합 셀의 None은 유지한다. 좌표 읽기가 누락되면 호출자가 원본 본문만 보내도록 되돌린다."""
    original = table.extract()
    cells = []
    for index, row in enumerate(table.rows):
        values = []
        for column, rect in enumerate(row.cells):
            value = None if rect is None else WHITESPACE.sub(" ", page.get_text("text", clip=pymupdf.Rect(rect), sort=True)).strip()
            if not value and original[index][column]:
                raise ValueError("표 셀의 원문을 좌표에서 읽지 못했습니다.")
            values.append(value)
        cells.append(values)
    return cells


def rental_keys(cells: list[list[str | None]]) -> set[tuple[str, str]]:
    """임대조건표에서 금액이 있는 줄의 (첫 칸, 둘째 칸)을 모은다. 병합 칸은 None이라 위 줄 값을 이어 쓴다.
    표마다 칸 구성이 달라 정확한 대상 수가 아니라 하한이다. 한 대상이 전환 조건으로 두 줄이어도 하나로 센다."""
    if not any(RENT_HEADER.search(compact(cell)) for row in cells[:3] for cell in row):
        return set()
    keys, last = set(), ["", ""]
    for row in cells:
        # 머리글 줄은 금액이 없어 건너뛰므로 주택형 자리에 머리글 낱말이 들어가지 않는다.
        if len(row) < 3 or not any(cell and AMOUNT_CELL.fullmatch(cell) and len(DIGIT.findall(cell)) >= 5 for cell in row):
            continue
        for column in (0, 1):
            if row[column]:
                last[column] = compact(row[column])
        # 쪽을 넘어 이어진 표는 첫 줄의 주택형 칸이 비어 나올 수 있다. 하한이 넘치지 않게 주택형을 모르는 줄은 세지 않는다.
        if last[0]:
            keys.add((last[0], last[1]))
    return keys


def compact(cell: str | None) -> str:
    return WHITESPACE.sub("", cell or "")
