"""PDF 쪽에서 표를 찾아 셀을 읽고, 공급표인지와 열 머리글의 역할(필드)을 판단한다(page_tables).

표 인식기는 병합 칸을 None, 실제 빈칸을 ''로 준다. 쪽을 고르고 프롬프트 글로 잇는 일은 pages가 한다.
"""

import logging
import re
from dataclasses import dataclass

import pymupdf

log = logging.getLogger(__name__)

_WHITESPACE = re.compile(r"\s+")
# 표 칸 글자는 콤마가 줄바꿈 뒤로 밀려 "41854000\n, ,"처럼 나와 숫자·콤마·공백만으로 된 칸을 금액으로 본다.
_AMOUNT_CELL = re.compile(r"[\d,\s]+")
_DIGIT = re.compile(r"\d")
_RENT_HEADER = re.compile(r"임대조건|월임대료")
_SUPPLY_TABLE_HEADER = re.compile(r"공급형|공급유형|주택형|모집\s*(?:호수|세대수|인원|가구)|임대보증금|월\s*임대료")
_AREA_CELL = re.compile(r"\d{2,3}\.\d{2,4}")
_MONEY_CELL = re.compile(r"\d{1,3}(?:,\d{3})+")
_TABLE_HEADER = re.compile(r"단지명|공급형|주택형|공급대상|공급계층|청약구분|전용|임대조건|임대보증금|월임대료")
_TABLE_HEADER_CELL = re.compile(
    r"단지명|공급형|주택형|주택구분|공급대상|공급계층|청약구분|모집(?:호수|세대수|인원|가구)|"
    r"일반공급|우선공급|예비자|당첨자|전용|공용|계약면적|건설(?:호수|세대수)|총세대수|"
    r"주차(?:장|대수|호수)|층수|난방|거주기간|합계|임대조건|임대보증금|월임대료"
)


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
    detail = _target_detail_column(header_rows, columns)
    if detail is not None:
        columns["targetDetail"] = detail
    return columns


def _target_detail_column(header_rows: list[list[str | None]], columns: dict[str, int]) -> int | None:
    """공급대상 머리글이 오른쪽 칸까지 병합돼 있으면 그 칸은 대상의 세부 구분(소득 있음·없음)이다."""
    target = columns.get("target")
    if target is None or target + 1 in columns.values():
        return None
    for row in header_rows:
        if target + 1 < len(row) and row[target] and re.search(r"공급대상|공급계층|청약대상", _compact(row[target])):
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
    if nonempty and all(_TABLE_HEADER_CELL.search(_compact(cell)) for cell in nonempty):
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


@dataclass(frozen=True)
class SupplyContinuation:
    """바로 앞 쪽에 공급표가 있었다는 기록. 다음 쪽의 머리글 없는 표가 이어짐인지 보고 열 역할을 물려받는다."""

    page: int
    # 그 쪽 공급표 첫 행의 칸 수 가운데 가장 큰 값. 이어짐 표는 칸 수가 같아야 한다.
    column_count: int
    # 그 쪽 마지막 공급표의 열 역할
    columns: dict[str, int]


@dataclass(frozen=True)
class TableRow:
    """공급표의 행 하나. 행 ID는 원본 쪽·표·행에서 정한다."""

    table: int
    id: str
    text: str
    # 머리글에서 바로 확인한 필드별 원문 셀
    values: dict[str, str]


@dataclass(frozen=True)
class PageTables:
    # 칸을 하나라도 읽지 못해 쪽 전체를 구조화하지 못했다. 이때 표는 하나도 없다.
    failed: bool
    boxes: list[tuple[float, float, float, float]]
    # 쪽의 모든 표, 그리고 본문 단계에 함께 보낼 표(공급표가 아니거나 공급표인지 불확실한 표)를 글로 옮긴 것
    rendered: list[str]
    notice_rendered: list[str]
    supply_tables: list[SupplyTable]
    supply_rows: list[TableRow]
    ambiguous_supply: bool
    complex_candidates: list[str]
    rental_keys: set[tuple[str, str]]
    # 다음 쪽에 넘길 이어짐 상태. 이 쪽에 공급표가 없으면 None이다.
    continuation: SupplyContinuation | None


def page_tables(page: pymupdf.Page, page_number: int, previous: SupplyContinuation | None) -> PageTables:
    """쪽의 표를 찾아 공급표를 가리고 열 역할과 행 값을 읽는다. previous는 바로 앞에 처리한 쪽의 이어짐 상태다."""
    detected, failed = detected_tables(page)
    if not failed:
        detected = _mark_supply_tables(detected, page_number, previous)
    page_supply_tables = [table for table in detected if table.supply]
    supply_tables, supply_rows, candidates, continuation = [], [], [], None
    if page_supply_tables:
        last_columns = previous.columns if previous and previous.page + 1 == page_number else {}
        for table_index, table in enumerate(detected, 1):
            if not table.supply:
                continue
            table_columns = table_field_columns(table.cells)
            if table_columns:
                last_columns = table_columns
            else:
                table_columns = last_columns
            supply_tables.append(SupplyTable(page_number, table_index, table.cells, table_columns, table.ambiguous))
            for row_index, row in enumerate(table.cells, 1):
                supply_rows.append(TableRow(
                    table_index, source_row_id(page_number, table_index, row_index), "\t".join(cell or "" for cell in row),
                    table_row_field_values(table.cells, row_index - 1, table_columns)))
            candidates.extend(_complex_name_candidates(table.cells))
        continuation = SupplyContinuation(
            page_number, max(len(table.cells[0]) if table.cells else 0 for table in page_supply_tables), last_columns)
    rental: set[tuple[str, str]] = set()
    for table in detected:
        rental |= _rental_keys(table.cells)
    return PageTables(
        failed=failed,
        boxes=[table.bbox for table in detected],
        rendered=[_render_table(table.cells, index) for index, table in enumerate(detected, 1)],
        notice_rendered=[_render_table(table.cells, index) for index, table in enumerate(detected, 1)
                         if not table.supply or table.ambiguous],
        supply_tables=supply_tables,
        supply_rows=supply_rows,
        ambiguous_supply=any(table.ambiguous for table in page_supply_tables),
        complex_candidates=candidates,
        rental_keys=rental,
        continuation=continuation,
    )


def detected_tables(page: pymupdf.Page) -> tuple[list[DetectedTable], bool]:
    """표 원문·좌표를 한 번만 읽는다. 하나라도 칸을 읽지 못하면 페이지 전체를 구조화 실패로 되돌린다."""
    try:
        tables = [DetectedTable(_table_cells(page, table), tuple(getattr(table, "bbox", page.rect)))
                  for table in page.find_tables().tables]
    except Exception:
        log.warning("%d쪽 표 인식에 실패해 텍스트만 보냅니다.", page.number + 1, exc_info=True)
        return [], True
    return tables, False


def _mark_supply_tables(tables: list[DetectedTable], page_number: int,
                        previous_supply: SupplyContinuation | None) -> list[DetectedTable]:
    """표 머리글을 우선 보고, 바로 앞 공급표와 열 수가 같은 머리글 없는 이어짐도 공급표로 분류한다."""
    marked = []
    for table in tables:
        columns = max((len(row) for row in table.cells), default=0)
        head = " ".join(_compact(cell) for row in table.cells[:4] for cell in row)
        explicit = bool(_SUPPLY_TABLE_HEADER.search(head)) and columns >= 3
        area_values = sum(len(_AREA_CELL.findall(cell or "")) for row in table.cells for cell in row)
        money_values = sum(len(_MONEY_CELL.findall(cell or "")) for row in table.cells for cell in row)
        continued = (
            previous_supply is not None
            and previous_supply.page + 1 == page_number
            and previous_supply.column_count == columns
            and (area_values >= 2 or money_values >= 3)
        )
        wide_data = columns >= 5 and (area_values >= 2 or money_values >= 3)
        supply = explicit or continued or wide_data
        marked.append(DetectedTable(table.cells, table.bbox, supply, wide_data and not explicit and not continued))
    return marked


def _render_table(cells: list[list[str | None]], index: int) -> str:
    return f"[표 {index}]\n" + "\n".join("\t".join(cell or "" for cell in row) for row in cells)


def source_row_id(page_number: int, table_number: int, row_number: int) -> str:
    return f"P{page_number}:T{table_number}:R{row_number}"


def _complex_name_candidates(cells: list[list[str | None]]) -> list[str]:
    candidates = []
    for row in cells:
        name = (row[0] or "").strip() if row else ""
        if (not name or _TABLE_HEADER.search(_compact(name)) or _compact(name).isdigit()
                or _AREA_CELL.search(name)):
            continue
        if not any(_compact(name) == _compact(candidate) for candidate in candidates):
            candidates.append(name)
    return candidates


def _table_cells(page: pymupdf.Page, table) -> list[list[str | None]]:
    """표 인식기의 글자 순서에서 소수점·쉼표가 뒤로 밀려 각 셀의 원문을 좌표로 읽는다.
    병합 셀의 None은 유지한다. 좌표 읽기가 누락되면 호출자가 원본 본문만 보내도록 되돌린다."""
    original = table.extract()
    cells = []
    for index, row in enumerate(table.rows):
        values = []
        for column, rect in enumerate(row.cells):
            value = None if rect is None else _WHITESPACE.sub(" ", page.get_text("text", clip=pymupdf.Rect(rect), sort=True)).strip()
            if not value and original[index][column]:
                raise ValueError("표 셀의 원문을 좌표에서 읽지 못했습니다.")
            values.append(value)
        cells.append(values)
    return cells


def _rental_keys(cells: list[list[str | None]]) -> set[tuple[str, str]]:
    """임대조건표에서 금액이 있는 줄의 (첫 칸, 둘째 칸)을 모은다. 병합 칸은 None이라 위 줄 값을 이어 쓴다.
    표마다 칸 구성이 달라 정확한 대상 수가 아니라 하한이다. 한 대상이 전환 조건으로 두 줄이어도 하나로 센다."""
    if not any(_RENT_HEADER.search(_compact(cell)) for row in cells[:3] for cell in row):
        return set()
    keys, last = set(), ["", ""]
    for row in cells:
        # 머리글 줄은 금액이 없어 건너뛰므로 주택형 자리에 머리글 낱말이 들어가지 않는다.
        if len(row) < 3 or not any(cell and _AMOUNT_CELL.fullmatch(cell) and len(_DIGIT.findall(cell)) >= 5 for cell in row):
            continue
        for column in (0, 1):
            if row[column]:
                last[column] = _compact(row[column])
        # 쪽을 넘어 이어진 표는 첫 줄의 주택형 칸이 비어 나올 수 있다. 하한이 넘치지 않게 주택형을 모르는 줄은 세지 않는다.
        if last[0]:
            keys.add((last[0], last[1]))
    return keys


def _compact(cell: str | None) -> str:
    return _WHITESPACE.sub("", cell or "")
