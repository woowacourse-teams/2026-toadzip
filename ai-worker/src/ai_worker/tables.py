"""PDF 쪽에서 표를 찾아 셀을 읽고, 공급표인지와 열 머리글의 역할(필드)을 판단한다(page_tables).

표 인식기는 병합 칸을 None, 실제 빈칸을 ''로 준다. 쪽을 고르고 프롬프트 글로 잇는 일은 pages가 한다.
"""

import logging
import re
from collections.abc import Iterable
from dataclasses import dataclass, field
from decimal import Decimal

import pymupdf

from ai_worker.checks import area_key

log = logging.getLogger(__name__)

_WHITESPACE = re.compile(r"\s+")
# 표 칸 글자는 콤마가 줄바꿈 뒤로 밀려 "41854000\n, ,"처럼 나와 숫자·콤마·공백만으로 된 칸을 금액으로 본다.
_AMOUNT_CELL = re.compile(r"[\d,\s]+")
_DIGIT = re.compile(r"\d")
_RENT_HEADER = re.compile(r"임대조건|월임대료")
_SUPPLY_TABLE_HEADER = re.compile(r"공급형|공급유형|주택형|모집\s*(?:호수|세대수|인원|가구)|임대보증금|월\s*임대료")
_AREA_CELL = re.compile(r"\d{2,3}\.\d{2,4}")
_MONEY_CELL = re.compile(r"\d{1,3}(?:,\d{3})+")
_AREA_NUMBER = re.compile(r"\d+(?:\.\d+)?")
_HOUSE_TYPE_HEADER = re.compile(r"주택형|주택타입|신청형")
_TABLE_HEADER = re.compile(r"단지명|공급형|주택형|공급대상|공급계층|청약구분|전용|임대조건|임대보증금|월임대료")
# 머리글 줄 뒤에 데이터 줄 글자(26·31)가 이어 붙을 수 있어 앞에서부터 보고, 뒤에 한글 낱말이 붙은 "공급형별 임대보증금" 같은 열은 뺀다.
# 다만 합계 줄 표기(총계·합계·계)가 머리글에 이어 붙은 "공급형별총계"는 주택형 열이다(대구연호 통합공공임대).
_HOUSING_TYPE_FALLBACK = re.compile(r"공급형별?(?:\([^)]*\))?(?:(?:총계|합계|소계|계)(?![가-힣])|(?![가-힣]))")
_MONEY_FIELDS = ("rentalDeposit", "monthlyRent", "convertedDeposit")
# 전환가능 보증금 한도액 칸은 증액(+)·감액(-) 한도를 한 칸에 함께 적거나("(+) 5,000,000 (-) 26,000,000") 위아래 줄로 나누고,
# 부호를 왼쪽 칸에 따로 적기도 한다("(+)" | "27,000,000"). 부호 표시는 "( ) , (- )"처럼 쪼개져 나오기도 한다.
_SIGN_MARK = re.compile(r"\(\s*[+-]?\s*\)")
_INCREASE_LIMIT = re.compile(r"\(\s*\+\s*\)\s*(\d[\d,]*)")
# 쉼표가 줄바꿈 뒤로 밀린 금액("9000000\n, ,")도 금액 칸이다.
_AMOUNT = re.compile(r"\d{1,3}(?:,\d{3})+|\d{4,}")
# 오른쪽 하위 열까지 덮는 병합 머리글. 하위 열 이름만으로는 뜻이 모호하다(최소·최대 보증금, 계약면적·주거 아래 전용).
_PARENT_HEADER = re.compile(r"최소|최대|전환|증액|계약면적|^주거$")
_TABLE_HEADER_CELL = re.compile(
    r"단지명|공급형|주택형|주택구분|공급대상|공급계층|청약구분|모집(?:호수|세대수|인원|가구)|"
    r"일반공급|우선공급|예비자|당첨자|전용|공용|계약면적|건설(?:호수|세대수)|총세대수|"
    r"주차(?:장|대수|호수)|층수|난방|거주기간|합계|임대조건|임대보증금|월임대료"
)


@dataclass(frozen=True)
class TableSubtype:
    """주택형(신청형) 행이 가리키는 단지 주택타입 묶음 하나. 전용면적(소수 둘째 자리)마다 하나이고,
    이름은 그 면적의 주택타입을 표 순서대로 '·'로 잇는다(59A·59A1·59C)."""

    name: str
    exclusive_area: float
    # 묶음의 면적 행들이 같은 값을 적었을 때만 둔다.
    common_area: float | None
    supply_area: float | None


@dataclass(frozen=True)
class TableRow:
    """공급표의 행 하나. 행 ID는 원본 쪽·표·행에서 정한다."""

    id: str
    text: str
    # 머리글에서 바로 확인한 필드별 원문 셀
    values: dict[str, str]
    # 금액 열이 신청자 묶음(가군·나군) 머리글로 나뉜 표에서 묶음 이름별 금액 칸. 대상 이름이 묶음 이름이면 이 칸과 대조한다.
    target_values: dict[str, dict[str, str]] = field(default_factory=dict)
    # 주택타입 열이 있는 표에서 이 행이 주택형(신청형) 칸이 시작하는 행이면 그 신청형의 세부 타입
    subtypes: tuple[TableSubtype, ...] = ()


@dataclass(frozen=True)
class SupplyTable:
    page: int
    number: int
    cells: list[list[str | None]]
    # 머리글이 없는 이어짐 표는 앞 공급표의 열 역할을 물려받는다.
    columns: dict[str, int]
    ambiguous: bool
    rows: list[TableRow]
    # 머리글 줄 다음 첫 데이터 행이 신청형 칸 없이 주택타입으로 시작한다. 열 구성이 같은 앞 표의 마지막 신청형이 이어진 것이다.
    continues_subtypes: bool = False

    @classmethod
    def of(cls, page: int, number: int, cells: list[list[str | None]], inherited_columns: dict[str, int] | None,
           ambiguous: bool) -> "SupplyTable":
        """셀에서 열 역할과 행 값을 읽어 공급표를 만든다. 머리글에서 열 역할을 찾지 못하면 inherited_columns를 쓴다."""
        columns = _table_field_columns(cells) or inherited_columns or {}
        subtypes = _house_type_subtypes(cells, columns)
        rows = [TableRow(source_row_id(page, number, index), "\t".join(cell or "" for cell in row),
                         _table_row_field_values(cells, index - 1, columns), _row_target_group_values(cells, index - 1),
                         subtypes.get(index - 1, ()))
                for index, row in enumerate(cells, 1)]
        return cls(page, number, cells, columns, ambiguous, rows, _starts_with_house_types(cells, columns))

    def last_housing_type_row_id(self) -> str | None:
        """신청형(주택형) 칸이 시작하는 마지막 행. 다음 표로 이어질 수 있는 신청형이다."""
        column = self.columns.get("housingType")
        if column is None:
            return None
        return next((row.id for row, cells in zip(reversed(self.rows), reversed(self.cells))
                     if (_cell(cells, column) or "").strip()), None)


@dataclass(frozen=True)
class DetectedTable:
    cells: list[list[str | None]]
    bbox: tuple[float, float, float, float]
    supply: bool = False
    ambiguous: bool = False


def _table_field_columns(rows: list[list[str | None]], row_index: int | None = None) -> dict[str, int]:
    """표 머리글 행을 열 위치로 바꾼다. 여러 열이 같은 뜻이면 그 필드는 일부러 매핑하지 않는다."""
    if not rows:
        return {}
    column_count = max((len(row) for row in rows), default=0)
    header_rows = rows[:min(row_index if row_index is not None else 6, 6)]
    fields = {
        # 리츠·통합공공임대 공급표는 단지명 대신 첫 열을 '블록'(동탄2 A40BL)이나 '단지'·'지구'로 둔다.
        "complexName": lambda header: bool(re.search(r"단지명|^(?:블록|단지|지구)(?!.*(?:면적|형|호수))", header)),
        "housingType": lambda header: bool(re.search(r"청약구분|주택형|공급유형", header)),
        # 공고의 주택형(신청형, 059.9000A)을 나눈 단지 주택타입(59A·59A1) 열
        "houseType": lambda header: bool(re.search(r"주택타입", header)),
        # 영구임대 임대조건표는 대상 열을 '적용구분'(「가」군·「나」군)으로 둔다.
        "target": lambda header: bool(re.search(r"공급대상|공급계층|청약대상|적용\s*구분", header)),
        "reserveCount": lambda header: bool(re.search(r"예비(?:입주자)?", header))
            and not re.search(r"잔여|기존|현재|대기", header),
        # 모집호수를 입주자·예비자로 나눈 표의 입주자 열도 당첨자 수다(예비입주자는 아니다). 모집수는 둘의 합이다(source_check).
        "supplyHouseholdCount": lambda header: bool(re.search(r"당첨(?:자|인원|호수)|(?<!예비)입주자(?![가-힣])", header)),
        "totalSupplyHouseholdCount": lambda header: bool(
            re.search(r"모집\s*(?:호수|인원|세대수)", header)
            # 모집호수 아래로 나뉜 입주자 하위 열("모집호수입주자")만 뺀다. 한 칸짜리 "입주자 모집호수"는 모집호수 열이다.
            and not re.search(r"예비|당첨|우선|일반|모집\s*(?:호수|인원|세대수).*입주자", header)
        ),
        # 계약면적·주거 아래의 "전용" 하위 머리글도 주거전용이다(사천벌리1: 세대별 계약면적 > 전용·주거 공용). 공용 열은 아니다.
        "exclusiveArea": lambda header: bool(
            re.search(r"주거전용|전용면적", header)
            or (re.search(r"(?:계약면적|주거).*전용", header) and not re.search(r"공용", header))
        ),
        "commonArea": lambda header: bool(re.search(r"주거공용", header)),
        # "공급면적 > 주거전용·주거공용"처럼 공급면적이 상위 병합 머리글이면 하위 열은 전용·공용이지 공급면적 합이 아니다.
        "supplyArea": lambda header: bool(re.search(r"공급면적", header) and not re.search(r"전용|공용", header)),
        "constructedHouseholdCount": lambda header: bool(re.search(r"건설(?:호수|세대수)|주택형별세대수", header)),
        "totalHouseholdCount": lambda header: bool(re.search(r"총세대수|전체세대수", header)),
        # 단지 소재지 열. 접수처·견본주택 위치는 단지 주소가 아니다.
        "address": lambda header: bool(re.search(r"소재지|건설위치|단지주소|단지위치|사업위치|공급위치|^위치$", header)
            and not re.search(r"접수|문의|견본|홍보|모델", header)),
        "parkingCount": lambda header: bool(re.search(r"주차대수|주차호수", header)),
        "rentalDeposit": lambda header: bool(
            re.search(r"임대보증금", header) and not re.search(r"전환|증액|최소|최대", header)
        ),
        "monthlyRent": lambda header: bool(
            re.search(r"월임대료", header) and not re.search(r"전환|증액|최소|최대", header)
        ),
        "convertedDeposit": lambda header: bool(
            not re.search(r"월임대료", header)
            and (re.search(r"(?:전환|증액|최대).*보증금|보증금.*(?:전환|증액|최대)", header)
                 # 증액·감액 조건을 나눈 표는 "증액 > 한도액"처럼 보증금 없이 한도만 적는다. 감액 한도는 전환보증금이 아니다.
                 or (re.search(r"(?:전환|증액).*한도", header) and not re.search(r"감액", header)))
        ),
    }
    columns: dict[str, int] = {}
    normalized_headers = table_column_headers(header_rows, column_count)
    for name, matches in fields.items():
        indexes = _matching_columns(normalized_headers, matches)
        # 주택형 열을 "공급형별(㎡)"로만 적은 표가 있다. "공급형"은 다른 머리글에도 섞여 다른 주택형 열이 없을 때만 본다.
        if name == "housingType" and not indexes:
            indexes = _matching_columns(normalized_headers, lambda header: bool(_HOUSING_TYPE_FALLBACK.match(header)))
        if name == "convertedDeposit" and len(indexes) > 1:
            indexes = _conversion_limit_columns(rows, normalized_headers, indexes)
        if len(indexes) == 1:
            columns[name] = indexes[0]
    # 하위 열 없는 모집호수 옆에 예비자 열이 따로 있으면 모집호수는 입주자(당첨자) 수다. 모집수는 둘의 합이라(사용자 결정)
    # 당첨자 열로 두어 원문 대조·채움이 합을 쓰게 한다. 모집호수 계·합계 열은 이미 합이다.
    # 머리글 줄 뒤에 데이터 줄 글자가 이어 붙을 수 있어(모집호수계13) 끝이 아니라 포함으로 본다.
    total = columns.get("totalSupplyHouseholdCount")
    if total is not None and "reserveCount" in columns and "supplyHouseholdCount" not in columns \
            and "계" not in normalized_headers[total]:
        columns["supplyHouseholdCount"] = columns.pop("totalSupplyHouseholdCount")
    # 단지명(소재지)처럼 한 열이 단지명이면서 위치이면 주소로 쓰지 않는다.
    if columns.get("address") is not None and columns.get("address") == columns.get("complexName"):
        del columns["address"]
    detail = _target_detail_column(header_rows, columns)
    if detail is not None:
        columns["targetDetail"] = detail
    return columns


def _conversion_limit_columns(rows: list[list[str | None]], headers: list[str], indexes: list[int]) -> list[int]:
    """전환보증금 후보 열이 여럿이면 한도액 열만 남긴다. "최대전환 시 임대조건 > 보증금"은 전환 뒤 총액이라 한도가 아니다.
    한도액 머리글이 부호 칸((+)·(-))과 금액 칸을 함께 덮으면 금액이 적힌 칸이 한도액 열이다."""
    limits = [column for column in indexes if "한도" in headers[column]]
    if len(limits) > 1:
        limits = [column for column in limits
                  if any(column < len(row) and _AMOUNT.search(row[column] or "") for row in rows)]
    return limits


def increase_limit(row: list[str | None], column: int) -> str | None:
    """전환보증금 열 칸에서 증액(+) 한도 금액 글자. 전환보증금은 증액 한도다. 부호 없이 금액만 적힌 칸은 그대로 두고,
    감액(-) 한도만 있거나 증액 한도가 '-'인 칸은 ''이다. 병합 칸은 None이다."""
    cell = _cell(row, column)
    if cell is None:
        return None
    sign = _cell(row, column - 1) if column > 0 else ""
    if sign and _SIGN_MARK.fullmatch(sign.strip()):
        cell = f"{sign} {cell}"
    if not _SIGN_MARK.search(cell):
        return cell
    match = _INCREASE_LIMIT.search(cell)
    return match.group(1) if match else ""


def _house_type_subtypes(cells: list[list[str | None]], columns: dict[str, int]) -> dict[int, tuple[TableSubtype, ...]]:
    """주택타입 열이 있는 표에서 주택형(신청형) 칸이 시작하는 행마다 세부 타입을 읽는다. 신청형 칸이 아래 행에 걸쳐
    병합(None)되면 그 행들의 주택타입도 같은 신청형이고, 주택타입 행의 면적 칸이 병합(None)이면 위 행의 면적을 이어받는다.
    면적 행과 이름 행이 어긋나 이름 없이 면적만 있는 행도 아래 주택타입들의 면적이다.
    주택타입 하나라도 면적을 숫자로 읽지 못하면(머리글 줄, 빈칸) 그 신청형은 세부 타입을 내지 않는다."""
    housing, house, area = columns.get("housingType"), columns.get("houseType"), columns.get("exclusiveArea")
    if housing is None or house is None or area is None:
        return {}
    # 신청형 칸이 시작하는 행 → (주택타입, 면적을 읽은 행) 목록. 면적을 모르는 주택타입이 있으면 None이다.
    blocks: dict[int, list[tuple[str, list[str | None]]] | None] = {}
    start, area_row = None, None
    for index, row in enumerate(cells):
        head = _cell(row, housing)
        if head is not None:  # 병합 칸만 None이다. 값이나 실제 빈칸이 있으면 앞 신청형 칸이 끝난다.
            start, area_row = (index if head.strip() else None), None
            if start is not None:
                blocks[start] = []
        members = blocks.get(start) if start is not None else None
        if members is None:
            continue
        cell = _cell(row, area)
        if cell is not None:
            area_row = row if _AREA_NUMBER.fullmatch(cell.strip()) else None
        name = _cell(row, house)
        if name and name.strip():
            if area_row is None:
                blocks[start] = None
            else:
                members.append((_WHITESPACE.sub(" ", name).strip(), area_row))
    return {start: _area_subtypes(members, columns) for start, members in blocks.items() if members}


def _starts_with_house_types(cells: list[list[str | None]], columns: dict[str, int]) -> bool:
    housing, house = columns.get("housingType"), columns.get("houseType")
    if housing is None or house is None:
        return False
    for row in cells:
        head, name = _cell(row, housing), _cell(row, house)
        # 머리글 줄(주택형·주택타입 머리글, 병합된 머리글 아래 줄)은 쪽마다 다시 적힐 수 있어 건너뛴다.
        if (head is None and name is None) or _HOUSE_TYPE_HEADER.search(_compact(head) + _compact(name)):
            continue
        return not (head or "").strip() and bool((name or "").strip())
    return False


def _area_subtypes(members: list[tuple[str, list[str | None]]], columns: dict[str, int]) -> tuple[TableSubtype, ...]:
    area, common, supply = columns["exclusiveArea"], columns.get("commonArea"), columns.get("supplyArea")
    return merge_subtypes(TableSubtype(name, float(row[area]), _area_cell(row, common), _area_cell(row, supply))
                          for name, row in members)


def merge_subtypes(subtypes: Iterable[TableSubtype]) -> tuple[TableSubtype, ...]:
    """세부 타입을 전용면적(소수 둘째 자리)마다 하나로 묶는다. 같은 면적은 단지 주택타입 하나로 잇기 때문이다.
    이름은 주택타입을 나온 순서대로 '·'로 잇고, 공용·공급면적은 묶인 값이 모두 같을 때만 둔다."""
    groups: dict[Decimal, list[TableSubtype]] = {}
    for subtype in subtypes:
        groups.setdefault(area_key(subtype.exclusive_area), []).append(subtype)
    merged = []
    for items in groups.values():
        names = dict.fromkeys(name for item in items for name in item.name.split("·"))
        commons = {item.common_area for item in items}
        supplies = {item.supply_area for item in items}
        merged.append(TableSubtype("·".join(names), items[0].exclusive_area,
                                   commons.pop() if len(commons) == 1 else None,
                                   supplies.pop() if len(supplies) == 1 else None))
    return tuple(merged)


def _area_cell(row: list[str | None], column: int | None) -> float | None:
    """면적 행의 공용·공급면적 칸. 병합·빈칸이면 정하지 않는다."""
    value = (_cell(row, column) or "").strip() if column is not None else ""
    return float(value) if _AREA_NUMBER.fullmatch(value) else None


def _cell(row: list[str | None], column: int) -> str | None:
    return row[column] if column < len(row) else ""


def _matching_columns(headers: list[str], matches) -> list[int]:
    return [column for column, header in enumerate(headers) if header and matches(header)]


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
            if cell and _PARENT_HEADER.search(_compact(cell)):
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


def _table_row_field_values(rows: list[list[str | None]], row_index: int,
                           inherited_columns: dict[str, int] | None = None) -> dict[str, str]:
    """표 머리글 행을 열 위치로 연결해, 모델이 낸 수치가 같은 의미의 원문 칸에 있는지 검증한다."""
    if not rows or row_index < 0 or row_index >= len(rows):
        return {}
    row = rows[row_index]
    if _is_header_row(row):
        return {}
    columns = _table_field_columns(rows, row_index)
    for name, column in (inherited_columns or {}).items():
        columns.setdefault(name, column)
    values = {name: row[column] for name, column in columns.items() if column < len(row) and row[column]}
    if "convertedDeposit" in values:
        limit = increase_limit(row, columns["convertedDeposit"])
        if limit:
            values["convertedDeposit"] = limit
        else:
            del values["convertedDeposit"]
    # 단지명 칸이 세로로 병합되면 아래 행 칸은 None이다(실제 빈칸은 ''). 병합 칸은 위 행 값과 같은 칸이라 이어받는다.
    column = columns.get("complexName")
    if column is not None and "complexName" not in values and column < len(row) and row[column] is None:
        merged = _merged_value_above(rows, row_index, column)
        if merged:
            values["complexName"] = merged
    _add_money_units(values, columns, table_column_headers(rows[:min(row_index, 6)], max(map(len, rows))))
    return values


def _merged_value_above(rows: list[list[str | None]], row_index: int, column: int) -> str | None:
    """세로 병합 칸의 값. 위로 올라가며 None이 아닌 첫 칸을 찾고, 머리글 줄이나 실제 빈칸을 만나면 값이 없다."""
    for above in range(row_index - 1, -1, -1):
        cell = rows[above][column] if column < len(rows[above]) else ""
        if cell is None:
            continue
        if _is_header_row(rows[above]) or not cell.strip():
            return None
        return cell
    return None


def _is_header_row(row: list[str | None]) -> bool:
    nonempty = [cell for cell in row if cell and cell.strip()]
    return bool(nonempty) and all(_TABLE_HEADER_CELL.search(_compact(cell)) for cell in nonempty)


def _add_money_units(values: dict[str, str], columns: dict[str, int], headers: list[str]) -> None:
    for name in _MONEY_FIELDS:
        column = columns.get(name)
        if column is not None and name in values:
            unit = re.search(r"만원|천원|원", headers[column])
            if unit:
                values[f"{name}Unit"] = unit.group()


def _row_target_group_values(rows: list[list[str | None]], row_index: int) -> dict[str, dict[str, str]]:
    """금액 열을 신청자 묶음(가군·나군, 우선·일반)으로 나눈 병합 머리글이 있으면 행의 묶음별 금액 칸을 읽는다.
    같은 머리글 줄의 병합 칸 둘 이상이 저마다 같은 금액 필드를 한 열씩 덮을 때만 묶음으로 본다.
    보증금은 묶음 안에서도 계 열만 임대보증금으로 읽힌다(계약금·잔금 열은 임대보증금 머리글이 아니다)."""
    if row_index <= 0 or row_index >= len(rows) or _is_header_row(rows[row_index]):
        return {}
    row = rows[row_index]
    header_rows = rows[:min(row_index, 6)]
    column_count = max(map(len, rows))
    for cells in header_rows:
        groups = []
        for start, label in enumerate(cells[:column_count]):
            end = start + 1
            while end < min(len(cells), column_count) and cells[end] is None:
                end += 1
            # 금액 머리글 자체가 병합된 칸(임대보증금 > 계·계약금·잔금)과 기본·최대 전환 같은 조건 구분은 신청자 묶음이 아니다.
            if (not label or not label.strip() or end - start < 2
                    or re.search(r"보증금|임대료", _compact(label)) or _PARENT_HEADER.search(_compact(label))):
                continue
            # 묶음이 덮는 열의 머리글만으로 묶음 안의 열 역할을 정한다.
            sub_rows = [header[start:end] for header in header_rows]
            columns = {name: start + column for name, column in _table_field_columns(sub_rows).items()
                       if name in _MONEY_FIELDS}
            if columns:
                groups.append((_WHITESPACE.sub(" ", label).strip(), columns,
                               [""] * start + table_column_headers(sub_rows, end - start)))
        if len(groups) >= 2 and all(columns.keys() == groups[0][1].keys() for _, columns, _ in groups):
            result = {}
            for label, columns, headers in groups:
                values = {name: row[column] for name, column in columns.items() if column < len(row) and row[column]}
                _add_money_units(values, columns, headers)
                if values:
                    result[label] = values
            return result
    return {}


@dataclass(frozen=True)
class SupplyContinuation:
    """바로 앞 쪽에 공급표가 있었다는 기록. 다음 쪽의 머리글 없는 표가 이어짐인지 보고 열 역할을 물려받는다."""

    page: int
    # 그 쪽 공급표 첫 행의 칸 수 가운데 가장 큰 값. 이어짐 표는 칸 수가 같아야 한다.
    column_count: int
    # 그 쪽 마지막 공급표의 열 역할
    columns: dict[str, int]


@dataclass(frozen=True)
class PageTables:
    # 칸을 하나라도 읽지 못해 쪽 전체를 구조화하지 못했다. 이때 표는 하나도 없다.
    failed: bool
    # 쪽에서 찾은 표마다의 경계. 본문 단계 글에서 표 안 글자를 뺄 때 쓴다.
    table_boxes: list[tuple[float, float, float, float]]
    # 본문 단계에 함께 보낼 표(공급표가 아니거나 공급표인지 불확실한 표)를 글로 옮긴 것
    notice_rendered: list[str]
    supply_tables: list[SupplyTable]
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
    supply_tables, candidates, continuation = [], [], None
    if page_supply_tables:
        last_columns = previous.columns if previous and previous.page + 1 == page_number else {}
        for table_index, table in enumerate(detected, 1):
            if not table.supply:
                continue
            supply_table = SupplyTable.of(page_number, table_index, table.cells, last_columns, table.ambiguous)
            last_columns = supply_table.columns
            supply_tables.append(supply_table)
            candidates.extend(_complex_name_candidates(table.cells))
        continuation = SupplyContinuation(
            page_number, max(len(table.cells[0]) if table.cells else 0 for table in page_supply_tables), last_columns)
    rental: set[tuple[str, str]] = set()
    for table in detected:
        rental |= _rental_keys(table.cells)
    return PageTables(
        failed=failed,
        table_boxes=[table.bbox for table in detected],
        notice_rendered=[_render_table(table.cells, index) for index, table in enumerate(detected, 1)
                         if not table.supply or table.ambiguous],
        supply_tables=supply_tables,
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
