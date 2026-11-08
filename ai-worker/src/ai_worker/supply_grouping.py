"""공급·임대 표의 셀 구조로 공급행을 코드가 묶는다.

표 인식기는 병합 칸을 None, 실제 빈칸을 ''로 준다. 주택형이나 공급대상이 적힌 행이 공급대상 행이고,
둘 다 비어 있는 행은 위 행의 이어짐(면적 줄, 쪼개진 칸, 소득 구분 줄, 전환 조건 줄)이다.
숫자는 셀에서 직접 읽어 모델이 숫자를 다시 쓰지 않게 한다. 출력은 모델 공급 단계와 같은 짧은 행 목록이라
supply_assembly.assemble이 같은 검산을 거쳐 관리자 계약으로 조립한다.
읽은 칸을 값으로 정하지 못하면 조용히 버리지 않고 미확인(unresolved)으로 남긴다.
표 구조를 확신하지 못하면 None을 돌려 모델 공급 단계로 넘긴다.
"""

import re
from dataclasses import dataclass, field

from ai_worker.tables import SupplyTable, source_row_id, table_column_headers

_COUNT_FIELDS = ("totalSupplyHouseholdCount", "supplyHouseholdCount", "reserveCount")
_AREA_FIELDS = ("exclusiveArea", "commonArea", "supplyArea")
_RENT_FIELDS = ("rentalDeposit", "monthlyRent", "convertedDeposit")
_INTEGER_FIELDS = (*_COUNT_FIELDS, "constructedHouseholdCount", *_RENT_FIELDS)
_VALUE_FIELDS = (*_COUNT_FIELDS, *_AREA_FIELDS, "constructedHouseholdCount", *_RENT_FIELDS)
_SUMMARY_WORDS = {"합계", "소계", "계"}
_NUMBER = re.compile(r"\d{1,3}(?:,\d{3})+|\d+(?:\.\d+)?")
_UNIT = re.compile(r"만원|천원|원")
_UNIT_SCALE = {"천원": 1000, "만원": 10000, "원": 1}
# 전환보증금은 증액 한도 열만 읽는다. "최대전환 시 보증금"은 전환 뒤 총액이라 계산 없이는 한도가 아니다.
_CONVERSION_LIMIT = re.compile(r"한도|증액")
_HOUSING_TOKEN = re.compile(r"[^\s,/]+")
_TARGET_TOKEN = re.compile(r"[^\s,/(]+(?:\s*\([^)]*\))?")
_NOT_A_NUMBER = "원문 칸을 숫자로 읽지 못함"


@dataclass(frozen=True)
class _Layout:
    """표의 열 머리글 정보. 머리글 없는 이어짐 표는 앞 표의 것을 물려받는다."""
    headers: dict[str, str]
    units: dict[str, str | None]


@dataclass
class _SourceRow:
    """표의 공급대상 행 하나와 그 이어짐 행들."""
    table: SupplyTable
    layout: _Layout
    row_id: str
    complex_name: str
    housing_type: str
    target: str
    # 이어받은 단지명·주택형·대상이 직접 적힌 행. 검산이 원문 칸을 찾을 수 있게 rowIds에 넣는다.
    origin_ids: list[str]
    cells: dict[str, str | None]
    variant: str | None = None
    continuation_ids: list[str] = field(default_factory=list)
    # 병합으로 위 행과 함께 쓰는 수량: 필드 → 값이 적힌 행
    shared_from: dict[str, "_SourceRow"] = field(default_factory=dict)
    # 병합된 면적 칸을 물려준 위 행. 원문 대조가 면적 값을 찾을 수 있게 rowIds에 넣는다.
    area_ids: list[str] = field(default_factory=list)
    # 이어짐 줄에 다른 값이 있어 하나로 정할 수 없는 필드
    conflicts: set[str] = field(default_factory=set)

    @property
    def name(self) -> str:
        return f"{self.target}({self.variant})" if self.variant else self.target


@dataclass(frozen=True)
class Grouped:
    compact: dict
    row_values: dict[str, dict[str, str]]
    warnings: list[str]
    # 머리글이 있는 수량표마다 (표, 머리글 줄 수, 수량 필드 → 열). 열의 뜻은 호출하는 쪽(supply_sources)이 Jev로 확인한다.
    count_columns: list[tuple[SupplyTable, int, dict[str, int]]]


def group(tables: list[SupplyTable], row_values: dict[str, dict[str, str]],
          tableless_pages: list[int]) -> Grouped | None:
    quantity_tables, rental_tables = [], []
    for table in tables:
        columns = table.columns
        keyed = "housingType" in columns and "target" in columns
        if keyed and table.ambiguous:
            return None  # 공급표인지 불확실한 표가 앞 표의 열 역할을 물려받았을 수 있다.
        if keyed and any(name in columns for name in _COUNT_FIELDS):
            quantity_tables.append(table)
        elif keyed and any(name in columns for name in _RENT_FIELDS):
            rental_tables.append(table)
        elif not table.ambiguous:
            return None
        # 소득 기준표처럼 공급표인지 불확실하고 공급 열도 없는 표는 무시한다.
    if any(_skipped_supply_row(table) for table in tables if table in quantity_tables or table in rental_tables):
        return None  # 값이 순수 숫자가 아닌 첫 행은 머리글과 구분되지 않아 빠질 수 있다.
    quantities = _read_rows(quantity_tables)
    if not quantities:
        return None
    rentals = [row for row in _read_rows(rental_tables) if any(_present(row.cells.get(name)) for name in _RENT_FIELDS)]
    if any(row.variant for row in quantities) and any(row.variant for row in rentals):
        return None  # 두 표의 소득 구분을 서로 맞추는 규칙은 아직 없다.
    row_values = {row_id: dict(values) for row_id, values in row_values.items()}

    matched: dict[int, list[_SourceRow]] = {}
    unmatched_pages = []
    for rental in rentals:
        owners = [quantity for quantity in quantities if _rental_applies(rental, quantity, quantities)]
        if not owners:
            unmatched_pages.append(rental.table.page)
        for quantity in owners:
            matched.setdefault(id(quantity), []).append(rental)

    rows, item_indexes = [], {}
    for quantity in quantities:
        matches = matched.get(id(quantity), [])
        variants = {rental.variant for rental in matches if rental.variant}
        if len(matches) > 1 and len(variants) == len(matches):
            # 소득 유무처럼 같은 대상이 임대조건으로 나뉘면 공급대상이 그만큼 있는 것이다(사용자 결정).
            parts = [(f"{quantity.name}({rental.variant})", rental) for rental in matches]
        else:
            parts = [(quantity.name, matches[0] if len(matches) == 1 else None)]
        indexes = []
        for name, rental in parts:
            item = {"rowIds": _source_ids(quantity), "complexName": quantity.complex_name,
                    "housingType": quantity.housing_type, "target": name, "unresolved": []}
            _fill(item, quantity, (*_AREA_FIELDS, "constructedHouseholdCount", *_RENT_FIELDS), row_values)
            if rental is not None:
                item["rowIds"] = _unique([*item["rowIds"], *_source_ids(rental)])
                _fill(item, rental, _RENT_FIELDS, row_values)
            elif len(matches) > 1:
                item["unresolved"] += [{"field": field_name, "reason": "같은 대상의 임대조건이 여러 줄이라 하나로 정하지 못함"}
                                       for field_name in _RENT_FIELDS[:2]]
            elif rentals:
                item["unresolved"] += [{"field": field_name, "reason": "임대조건표에서 이 대상의 행을 찾지 못함"}
                                       for field_name in _RENT_FIELDS[:2]]
            indexes.append(len(rows))
            rows.append(item)
        item_indexes[id(quantity)] = indexes

    shared_counts = _assign_counts(quantities, rows, item_indexes)
    for item in rows:
        if not item["unresolved"]:
            del item["unresolved"]

    quantity_pages = {table.page for table in quantity_tables}
    supply_pages = {table.page for table in tables}
    warnings = [
        f"원본 {page}쪽의 공급표를 표로 읽지 못해 공급행이 빠졌을 수 있습니다. 원본을 확인해 주세요."
        for page in tableless_pages if page - 1 in quantity_pages and page not in supply_pages
    ]
    warnings += [f"원본 {page}쪽 임대조건 행을 공급행과 잇지 못했습니다. 원본을 확인해 주세요."
                 for page in _unique(unmatched_pages)]
    # 전환보증금 열이 전환 뒤 총액이면 대상마다 미확인을 쌓으면 다른 미확인이 묻힌다. 표 단위로 한 줄만 알린다(사용자 결정).
    total_pages = sorted({row.table.page for row in [*quantities, *rentals]
                          if _present(row.cells.get("convertedDeposit")) and not _is_conversion_limit(row)})
    if total_pages:
        warnings.append(f"원본 {', '.join(map(str, total_pages))}쪽 전환보증금 열은 증액 한도가 아닌 전환 뒤 금액이라 "
                        "비워 두었습니다. 필요하면 원본에서 확인해 주세요.")
    count_columns = []
    for table in quantity_tables:
        start = _data_start(table.cells, table.columns)
        if start > 0:  # 머리글 없는 이어짐 표는 앞 표와 같은 열이라 다시 묻지 않는다.
            count_columns.append((table, start, {name: table.columns[name] for name in _COUNT_FIELDS
                                                 if name in table.columns}))
    return Grouped({"data": {"rows": rows}, "sharedCounts": shared_counts}, row_values, warnings, count_columns)


def _assign_counts(quantities: list[_SourceRow], rows: list[dict], item_indexes: dict[int, list[int]]) -> list[dict]:
    """병합된 수량 칸은 덮은 대상들의 공통 수량으로 남기고 대상별로 복제하지 않는다."""
    shared_counts = []
    for field_name in _COUNT_FIELDS:
        groups: dict[int, tuple[_SourceRow, list[_SourceRow]]] = {}
        for quantity in quantities:
            if field_name not in quantity.table.columns:
                continue
            origin = quantity.shared_from.get(field_name, quantity)
            if _present(origin.cells.get(field_name)):
                groups.setdefault(id(origin), (origin, []))[1].append(quantity)
        for origin, members in groups.values():
            indexes = [index for member in members for index in item_indexes[id(member)]]
            count = _number(origin.cells.get(field_name), field_name, None)
            if field_name in origin.conflicts or count is None:
                reason = "이어지는 줄에 다른 값이 있어 하나로 정하지 못함" if field_name in origin.conflicts else _NOT_A_NUMBER
                for index in indexes:
                    rows[index]["unresolved"].append({"field": field_name, "reason": reason})
                continue
            if field_name == "totalSupplyHouseholdCount":
                # 공급행 전체 모집수는 한 칸이 그 주택형의 모든 대상을 덮을 때만이다. 대상마다 따로 적힌 수는 합계가 아니다.
                keys = {_supply_key(member) for member in members}
                covered = [quantity for quantity in quantities if _supply_key(quantity) in keys]
                if len(keys) == 1 and len(covered) == len(members):
                    rows[indexes[0]][field_name] = count
                else:
                    for index in indexes:
                        rows[index]["unresolved"].append(
                            {"field": field_name, "reason": "모집수가 대상별로 나뉘어 공급행 전체 모집수로 정하지 못함"})
            elif len(indexes) == 1:
                rows[indexes[0]][field_name] = count
            else:
                shared_counts.append({"rowIndexes": indexes, "field": field_name, "count": count,
                                      "rowIds": [origin.row_id]})
    return shared_counts


def _fill(item: dict, row: _SourceRow, names: tuple[str, ...], row_values: dict[str, dict[str, str]]) -> None:
    for name in names:
        if name not in row.table.columns:
            continue
        cell = row.cells.get(name)
        if not _present(cell):
            continue
        reason = None
        unit = row.layout.units.get(name)
        if name == "convertedDeposit" and not _is_conversion_limit(row):
            continue  # 표 단위 경고 1줄로 알린다(group 참조).
        if name in row.conflicts:
            reason = "이어지는 줄에 다른 값이 있어 하나로 정하지 못함"
        elif name in _RENT_FIELDS and unit is None:
            reason = "금액 단위를 표에서 확인하지 못함"
        value = None if reason else _number(cell, name, unit)
        if value is None:
            item["unresolved"].append({"field": name, "reason": reason or _NOT_A_NUMBER})
            continue
        item[name] = value
        if name in _RENT_FIELDS:
            # 머리글 없는 이어짐 표는 행 값에 단위가 없어 검산이 원 단위로 볼 수 있다.
            row_values.setdefault(row.row_id, {})[f"{name}Unit"] = unit


def _is_conversion_limit(row: _SourceRow) -> bool:
    return bool(_CONVERSION_LIMIT.search(row.layout.headers.get("convertedDeposit", "")))


def _rental_applies(rental: _SourceRow, quantity: _SourceRow, quantities: list[_SourceRow]) -> bool:
    """임대조건 행의 한 칸에 주택형·대상이 여럿 적혀 있으면(예: 35B1 35C1 35D1) 그 각각에 같은 조건을 쓴다."""
    if not _same_type(rental, quantity):
        return False
    left, right = _compact(rental.complex_name), _compact(quantity.complex_name)
    if left == right:
        return True
    # 쪽을 넘으며 단지명이 잘린 경우(용인 / 용인 영덕)는 같은 주택형·대상을 가진 단지가 하나뿐일 때만 잇는다.
    if not left or not right.startswith(left):
        return False
    return len({_compact(row.complex_name) for row in quantities
                if _compact(row.complex_name).startswith(left) and _same_type(rental, row)}) == 1


def _same_type(rental: _SourceRow, quantity: _SourceRow) -> bool:
    return (_compact(quantity.housing_type) in _names(rental.housing_type, _HOUSING_TOKEN)
            and _compact(quantity.target) in _names(rental.target, _TARGET_TOKEN))


def _names(cell: str, token: re.Pattern) -> set[str]:
    return {_compact(cell), *(_compact(value) for value in token.findall(cell))}


def _read_rows(tables: list[SupplyTable]) -> list[_SourceRow]:
    """표를 쪽 순서로 읽어 공급대상 행을 만든다. 단지명·주택형·대상은 쪽을 넘어서도 위 행에서 이어받는다."""
    rows: list[_SourceRow] = []
    inherited: dict[str, tuple[str, str]] = {}  # 필드 → (값, 값이 적힌 행)
    layout: _Layout | None = None
    for table in tables:
        columns = table.columns
        start = _data_start(table.cells, columns)
        if start > 0 or layout is None:
            layout = _table_layout(table.cells[:start], columns, max(map(len, table.cells), default=0))
        for index in range(start, len(table.cells)):
            cells = table.cells[index]
            value = {name: cells[column] if column < len(cells) else None for name, column in columns.items()}
            row_id = source_row_id(table.page, table.number, index + 1)
            complex_name, housing, target = value.get("complexName"), value.get("housingType"), value.get("target")
            detail = value.get("targetDetail")
            if any(_compact(text) in _SUMMARY_WORDS for text in (complex_name, housing, target)) \
                    or _compact(housing) == "-":
                continue  # 합계·소계 행
            if not _present(housing) and not _present(target):
                parent = rows[-1] if rows else None
                if parent is None:
                    continue
                if _present(detail) and (parent.variant is not None and detail.strip() != parent.variant
                                         or any(_present(value.get(name)) for name in (*_COUNT_FIELDS, *_RENT_FIELDS))):
                    # 소득 구분 줄: 위 대상과 같은 공급대상의 다른 조건. 수량 칸이 병합돼 비어 있어도 대상이다.
                    row = _SourceRow(table, layout, row_id, parent.complex_name, parent.housing_type, parent.target,
                                     _unique([parent.row_id, *parent.origin_ids]), value, detail.strip())
                    _share_merged_counts(row, parent, columns)
                    for name in _RENT_FIELDS:
                        if name in columns and value.get(name) is None:
                            row.cells[name] = parent.cells.get(name)  # 병합된 금액 칸은 위 줄과 같은 값이다.
                    rows.append(row)
                    continue
                parent.continuation_ids.append(row_id)
                for name in _VALUE_FIELDS:
                    if not _present(value.get(name)):
                        continue
                    if not _present(parent.cells.get(name)):
                        parent.cells[name] = value[name]  # 격자로 쪼개진 칸
                    elif name not in _AREA_FIELDS and _compact(parent.cells[name]) != _compact(value[name]):
                        parent.conflicts.add(name)  # 면적 줄은 첫 면적이 대표, 그 밖의 다른 값은 정하지 못한다.
                continue
            origin_ids = []
            for name, current in (("complexName", complex_name), ("housingType", housing), ("target", target)):
                if _present(current):
                    inherited[name] = (current, row_id)
                elif name in inherited:
                    origin_ids.append(inherited[name][1])
            if "housingType" not in inherited or "target" not in inherited:
                continue
            row = _SourceRow(table, layout, row_id, inherited.get("complexName", ("", ""))[0],
                             inherited["housingType"][0], inherited["target"][0], _unique(origin_ids), value,
                             detail.strip() if _present(detail) else None)
            previous = rows[-1] if rows and rows[-1].table.columns == columns else None
            if previous is not None:
                _share_merged_counts(row, previous, columns)
                _share_merged_areas(row, previous, columns, next_page=previous.table is not table and index == start == 0)
            rows.append(row)
    return rows


def _skipped_supply_row(table: SupplyTable) -> bool:
    """머리글로 넘긴 행 가운데 주택형 칸에 숫자가 있는 행이 있으면 공급행을 머리글로 잘못 본 것이다."""
    column = table.columns["housingType"]
    return any(column < len(row) and row[column] and re.search(r"\d", row[column])
               for row in table.cells[:_data_start(table.cells, table.columns)])


def _share_merged_counts(row: _SourceRow, previous: _SourceRow, columns: dict[str, int]) -> None:
    for name in _COUNT_FIELDS:
        if name in columns and row.cells.get(name) is None:
            row.shared_from[name] = previous.shared_from.get(name, previous)


def _share_merged_areas(row: _SourceRow, previous: _SourceRow, columns: dict[str, int], next_page: bool) -> None:
    """병합된 면적 칸(None)은 위 행과 같은 면적이다. 쪽을 넘은 병합 칸은 머리글 없는 이어짐 표의 첫 행에서 빈칸('')으로
    읽히므로 그 행의 면적 칸이 모두 비었을 때만 위 행 값을 쓴다(머리글이 다시 있는 새 표는 이어짐이 아니다). 면적 칸을 이어받지 않으면 단지명이 적힌 행의
    다른 주택형 면적이 원문 대조에서 대신 쓰일 수 있다."""
    names = [name for name in _AREA_FIELDS if name in columns]
    if _compact(row.complex_name) != _compact(previous.complex_name):
        return
    blank_block = next_page and all(row.cells.get(name) in (None, "") for name in names)
    for name in names:
        cell = row.cells.get(name)
        if (cell is None or blank_block) and _present(previous.cells.get(name)):
            row.cells[name] = previous.cells[name]
            row.area_ids = _unique([*row.area_ids, previous.row_id, *previous.area_ids])


def _data_start(cells: list[list[str | None]], columns: dict[str, int]) -> int:
    """머리글 다음 첫 행: 수량·면적·금액 칸이 숫자 하나로만 된 첫 행."""
    value_columns = [column for name, column in columns.items() if name in _VALUE_FIELDS]
    for index, row in enumerate(cells):
        if any(column < len(row) and row[column] and _NUMBER.fullmatch(row[column].strip()) for column in value_columns):
            return index
    return len(cells)


def _table_layout(header_rows: list[list[str | None]], columns: dict[str, int], column_count: int) -> _Layout:
    headers = table_column_headers(header_rows, column_count)
    named = {name: headers[column] for name, column in columns.items() if column < len(headers)}
    units = {}
    for name in _RENT_FIELDS:
        unit = _UNIT.search(named.get(name, ""))
        units[name] = unit.group() if unit else None
    return _Layout(named, units)


def _source_ids(row: _SourceRow) -> list[str]:
    return _unique([row.row_id, *row.continuation_ids, *row.origin_ids, *row.area_ids])


def _supply_key(row: _SourceRow) -> tuple[str, str]:
    return _compact(row.complex_name), _compact(row.housing_type)


def _number(cell: str | None, field_name: str, unit: str | None) -> int | float | None:
    if not _present(cell):
        return None
    text = cell.strip()
    if not _NUMBER.fullmatch(text):
        return None
    value = float(text.replace(",", "")) * (_UNIT_SCALE.get(unit or "원", 1) if field_name in _RENT_FIELDS else 1)
    if field_name in _INTEGER_FIELDS:
        return int(value) if value.is_integer() else None
    return value


def _compact(value: str | None) -> str:
    return re.sub(r"[\s,，、․·「」]+", "", value or "")


def _present(value: str | None) -> bool:
    """'-'는 해당 없음을 뜻하는 칸이라 값이 없는 것으로 본다."""
    return isinstance(value, str) and bool(value.strip()) and value.strip() != "-"


def _unique(values: list[str]) -> list[str]:
    return list(dict.fromkeys(values))
