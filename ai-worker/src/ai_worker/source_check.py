"""공급표 모델의 모집 단위·공급대상 값을 원문 표 칸과 대조한다(원문 대조 검산).

단위·대상의 근거(원문 행 ID·인용·쪽)가 원문 표 행에서 확인되면 단지명·주택형·공급대상 표기는 같은 의미의 원문 칸과,
수치는 같은 의미의 머리글 열과 비교한다. 확인되지 않은 값은 비우고 그 단위·대상의 미확인(Finding)으로 남긴다.
"""

import re
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation

from ai_worker import pages, tables
from ai_worker.supply_sheet import AMOUNT_FIELDS, AREA_FIELDS, TOTAL, Cite, Entry, Finding, Unit

_UNIT_NUMBERS = (TOTAL, *AREA_FIELDS)
_ENTRY_NUMBERS = ("supplyHouseholdCount", "reserveCount", *AMOUNT_FIELDS)
# 대조하는 표기: (속성, 같은 의미의 원문 칸, 미확인 필드, 확인하지 못했을 때 둘 값)
_UNIT_TEXTS = (("name", "housingType", "name", None), ("complex_name", "complexName", "complexName", ""))
_ENTRY_TEXTS = (("label", "target", "label", ""),)


def check_source(owner: Unit | Entry, row_ids: set[str], cite: Cite | None, selected: pages.PromptPages,
                 headings: "Headings | None", confirmed: dict) -> tuple[list[Finding], str | None]:
    """모집 단위나 공급대상 하나를 원문과 대조한다. row_ids는 그 값들이 적힌 원문 표 행, cite는 그 전체의 첫 근거다.
    근거(행 ID·인용·쪽)를 원문 표 행에서 확인하면 값을 같은 의미의 원문 칸과 대조하고, 확인하지 못하면 모든 값을 비운다.
    confirmed는 원문 칸 하나에는 없지만 코드가 원문 칸들에서 정한 값(주택타입 행 수량의 합, 병합 모집 단위의 공통 형명)이다.
    그 필드는 원문 칸 대신 이 값과 대조하고, None이면 어떤 값도 확인하지 못한다.
    headings는 단지명 열이 없는 표의 단지명을 표 위 가장 가까운 제목과 대조할 때 쓴다(Headings).
    확인하지 못한 값은 비우고 미확인으로 돌려준다. 근거를 확인하지 못하면 그 사유도 돌려준다(조립이 경고로 알린다)."""
    findings: list[Finding] = []
    reason = _unsupported(row_ids, cite, selected.supply_row_text)
    if reason is None:
        _check_texts(owner, row_ids, selected, headings, confirmed, findings)
        _check_numbers(owner, row_ids, selected, confirmed, findings)
        return findings, None
    findings.append(Finding(owner, "", reason))
    for attribute, _, name, empty in _texts(owner):
        value = getattr(owner, attribute)
        if isinstance(value, str) and value:
            setattr(owner, attribute, empty)
            findings.append(Finding(owner, name, f"{reason}; 문자열 원문을 확인하지 못함"))
    for name in _numbers(owner):
        if owner.values.get(name) is not None:
            owner.values[name] = None
            findings.append(Finding(owner, name, f"{reason}; 수치 원문을 확인하지 못함"))
    return findings, reason


def _unsupported(row_ids: set[str], cite: Cite | None, row_text: dict[str, str]) -> str | None:
    """근거를 원문 표 행에서 확인하지 못한 사유. 확인하면 None이다."""
    unknown = row_ids - row_text.keys()
    quote = cite.quote if cite is not None else None
    quote_text = re.sub(r"\s+", "", quote) if isinstance(quote, str) else ""
    cited_text = re.sub(r"\s+", "", "".join(row_text[row_id] for row_id in sorted(row_ids) if row_id in row_text))
    quoted = bool(quote_text) and all(token in cited_text for token in re.split(r"\s+", quote) if token)
    source_pages = {int(match.group(1)) for row_id in row_ids if (match := re.match(r"P(\d+):T\d+:R\d+", row_id))}
    if row_ids and not unknown and quoted and (not source_pages or cite.page in source_pages):
        return None
    if not row_ids:
        return "원문 표 행 ID를 확인하지 못함"
    if unknown:
        return "응답의 원문 표 행 ID가 입력 표와 일치하지 않음"
    if not quoted:
        return "근거 인용을 지정한 원문 표 행에서 확인하지 못함"
    return "근거 쪽과 원문 표 행 ID의 쪽이 일치하지 않음"


def _texts(owner: Unit | Entry) -> tuple[tuple[str, str, str, str | None], ...]:
    return _UNIT_TEXTS if isinstance(owner, Unit) else _ENTRY_TEXTS


def _numbers(owner: Unit | Entry) -> tuple[str, ...]:
    return _UNIT_NUMBERS if isinstance(owner, Unit) else _ENTRY_NUMBERS


def _check_texts(owner: Unit | Entry, row_ids: set[str], selected: pages.PromptPages, headings: "Headings | None",
                 confirmed: dict, findings: list[Finding]) -> None:
    """단지명·주택형·공급대상은 해당 의미의 원문 셀과 비교한다. 단지명 칸이 없는 행의 단지명은 표 위 가장 가까운 제목과 비교한다."""
    values = selected.supply_row_values
    for attribute, source_field, name, empty in _texts(owner):
        value = getattr(owner, attribute)
        if not isinstance(value, str) or not value or confirmed.get(name) == value:
            continue
        if any(normalize_source_text(cell) == normalize_source_text(value)
               for cell in source_texts(source_field, row_ids, values, selected.supply_row_target_values)):
            continue
        if source_field == "complexName" and headings is not None and headings.confirms(value, row_ids, values):
            continue
        setattr(owner, attribute, empty)
        findings.append(Finding(owner, name, "추출한 값이 같은 의미의 원문 표 열에서 확인되지 않음"))


def _check_numbers(owner: Unit | Entry, row_ids: set[str], selected: pages.PromptPages, confirmed: dict,
                   findings: list[Finding]) -> None:
    """수치는 같은 의미의 머리글 열 칸과 같아야 한다. 대상은 그 대상의 신청자 묶음 열 칸과 대조한다(target_cells)."""
    label = owner.label if isinstance(owner, Entry) else None
    for name in _numbers(owner):
        value = owner.values.get(name)
        if value is None:
            continue
        if name in confirmed:
            if confirmed[name] is not None and value == confirmed[name]:
                continue
        elif any(source_cell_matches(value, target_cells(label, row_id, selected.supply_row_values,
                                                         selected.supply_row_target_values), name)
                 for row_id in row_ids):
            continue
        owner.values[name] = None
        findings.append(Finding(owner, name, "추출한 수치가 같은 의미의 원문 표 열에서 확인되지 않음"))


@dataclass(frozen=True)
class Headings:
    """단지명 열 없이 블록 제목(■ 광주선운2 A-1블록) 아래 표를 둔 공고의 단지명 대조. above는 행 ID → 같은 쪽에서 그 표 위에
    있는 글, names는 대조할 단지명 후보(모델 응답 단지명과 LH 공급행의 단지명)다. 모델은 표 위 제목을 보지 못하고 LH 행에서
    단지명을 얻으므로, LH 단지명을 후보에 넣어야 모든 행에 한 블록 이름을 붙인 응답을 거를 수 있다."""

    above: dict[str, str]
    names: tuple[str, ...]
    # 표 위 글들에서 찾히는 서로 다른 후보가 둘 이상인지. 후보가 하나뿐이면(LH 행이 없거나 LH 단지명이 제목과 글자가 다른데
    # 모델이 모든 행에 한 블록 이름을 붙인 경우) 가장 가까운지 가릴 수 없어 대조하지 않는다. 같은 블록의 두 표기
    # (A-1블록 ⊂ 광주선운2 A-1블록)는 하나로 센다.
    usable: bool = field(init=False, default=False)

    def __post_init__(self):
        texts = {normalize_source_text(text) for text in self.above.values()}
        found = {candidate for candidate in {normalize_source_text(item) for item in self.names} - {""}
                 if any(candidate in text for text in texts)}
        distinct = {candidate for candidate in found if not any(candidate != other and candidate in other for other in found)}
        object.__setattr__(self, "usable", len(distinct) > 1)

    def confirms(self, name: object, row_ids, row_values: dict[str, dict[str, str]]) -> bool:
        """단지명 칸이 없는 행의 단지명이 행 ID 표 위의 가장 가까운 단지명인지. 단지명 칸이 있는 행은 칸과 대조하므로 아니다."""
        if not self.usable or not isinstance(name, str) or not name.strip() \
                or source_texts("complexName", row_ids, row_values):
            return False
        return any(nearest_heading(name, self.above.get(row_id, ""), self.names) for row_id in row_ids)


def nearest_heading(name: str, heading: str, names) -> bool:
    """표 위 글(heading)에서 name이 후보 단지명 가운데 표에 가장 가깝게(가장 늦게) 끝나는지. 끝이 같으면 긴 이름이다.
    A-3블록 표의 행에 위쪽 A-1블록 이름을 붙인 값, 블록 제목 안에 들어 있는 지구명(광주선운2)만 낸 값은 받지 않는다."""
    text = normalize_source_text(heading)
    ends = {}
    for candidate in {normalize_source_text(item) for item in (*names, name) if isinstance(item, str)} - {""}:
        at = text.rfind(candidate)
        if at >= 0:
            ends[candidate] = at + len(candidate)
    own = normalize_source_text(name)
    return own in ends and max(ends, key=lambda candidate: (ends[candidate], len(candidate))) == own


def normalize_source_text(value: str) -> str:
    value = value.replace("「", "").replace("」", "")
    return re.sub(r"[\s,，、․·]+", "", value)


def numeric_cell_matches(value: object, cell: str, field: str, unit: str | None = None) -> bool:
    if not isinstance(cell, str) or not isinstance(value, (int, float)) or isinstance(value, bool):
        return False
    try:
        expected = Decimal(str(value))
    except InvalidOperation:
        return False
    try:
        actual = Decimal(re.sub(r"[^\d.-]", "", cell).replace(",", ""))
    except InvalidOperation:
        return False
    scale = 1
    if field in ("rentalDeposit", "monthlyRent", "convertedDeposit"):
        unit = unit or ("만원" if "만원" in cell else "천원" if "천원" in cell else "원")
        scale = {"천원": 1000, "만원": 10000}.get(unit, 1)
    return actual * scale == expected


HOUSEHOLD_COUNTS = ("totalSupplyHouseholdCount", "supplyHouseholdCount")


def source_cell_matches(value: object, cells: dict[str, str], field: str) -> bool:
    """수치가 한 원문 행의 같은 의미 칸과 같은지 본다. 모집수(공급행 totalSupplyHouseholdCount, 대상 supplyHouseholdCount)는
    입주자(당첨자)와 예비자를 합한 이번 공고 모집수다(사용자 결정). 표의 모집호수 칸(입주자·예비자를 나눈 표의 '계')이나
    당첨자 칸(예비자 칸이 있으면 둘의 합)과 같으면 확인된 값이다. 예비자 수만은 reserveCount다."""
    if field not in HOUSEHOLD_COUNTS:
        return numeric_cell_matches(value, cells.get(field, ""), field, cells.get(f"{field}Unit"))
    candidates = [cells.get("totalSupplyHouseholdCount", "")]
    recruited = recruitment_count(cells)
    if recruited is not None:
        candidates.append(str(recruited))
    return any(numeric_cell_matches(value, cell, field) for cell in candidates)


def recruitment_count(cells: dict[str, str]) -> int | None:
    """한 원문 행의 이번 공고 모집수. 모집호수 칸(계)이 있으면 그 값, 없으면 당첨자 칸과 예비자 칸의 합이다(source_cell_matches와 같은 규칙).
    공급호수를 우선·일반 공급으로 나눈 표는 두 칸의 합이다. 한 칸이라도 비었거나 병합으로 위 행에서 내려왔으면 정하지 않는다."""
    total = count_cell(cells.get("totalSupplyHouseholdCount"))
    if total is not None:
        return total
    winners = count_cell(cells.get("supplyHouseholdCount"))
    if winners is not None:
        return winners + (count_cell(cells.get("reserveCount")) or 0)
    priority, general = count_or_dash(cells.get("priorityCount")), count_or_dash(cells.get("generalCount"))
    return None if priority is None or general is None else priority + general


def count_or_dash(value: object) -> int | None:
    """수량 칸의 정수. '-'만 적힌 칸은 0이다(그 묶음에 배정한 호수가 없음)."""
    if isinstance(value, str) and re.fullmatch(r"\s*[-－–]\s*", value):
        return 0
    return count_cell(value)


def count_cell(value: object) -> int | None:
    """수량 칸의 정수. 숫자만(쉼표 허용) 적힌 칸이 아니면 None이다."""
    if not isinstance(value, str) or not re.fullmatch(r"\s*\d[\d,]*\s*", value):
        return None
    return int(value.replace(",", "").strip())


def source_texts(field: str, row_ids, row_values: dict[str, dict[str, str]],
                 target_values: dict[str, dict[str, dict[str, str]]] | None = None) -> list[str]:
    """행 ID들의 같은 의미 원문 칸. 공급대상 머리글 아래 세부 구분 칸(소득 있음·없음)이 따로 있으면
    공급대상은 둘을 합친 이름(청년(소득 없음))이다. 금액 열을 나눈 신청자 묶음 머리글(가군(생계·의료급여수급자 등))도
    공급대상 이름이고, 괄호 설명을 뺀 이름(가군)으로도 쓴다.
    주택형(신청형)과 주택타입 열이 따로 있는 표에서 행 ID들이 주택타입 하나만 가리키면 그 주택타입(84B)도 주택형 표기다.
    공급행을 주택타입마다 나눠 뽑은 경우다(supply_assembly가 행 단위에 맞는 열을 고른다)."""
    values = [row_values.get(row_id, {}).get(field) for row_id in row_ids]
    texts = [value for value in values if isinstance(value, str) and value.strip()]
    if field == "housingType":
        house_types = {row_values.get(row_id, {}).get("houseType") for row_id in row_ids} - {None}
        house_types = {value for value in house_types if value.strip()}
        if len({normalize_source_text(value) for value in house_types}) == 1:
            texts += sorted(house_types)
    if field == "target":
        details = [row_values.get(row_id, {}).get("targetDetail") for row_id in row_ids]
        # '공급형 | 주택형' 표의 공급형 칸 괄호 구분(55 (주거약자용))은 그 행 공급대상의 구분이다(supply_grouping).
        details += [tables.supply_type_qualifier(row_values.get(row_id, {}).get("supplyType")) for row_id in row_ids]
        texts += [f"{text}({detail})" for text in list(texts) for detail in details if detail]
        texts += [name for row_id in row_ids for label in (target_values or {}).get(row_id, {})
                  for name in _group_names(label)]
    return list(dict.fromkeys(texts))


def target_cells(target: object, row_id: str, row_values: dict[str, dict[str, str]],
                 target_values: dict[str, dict[str, dict[str, str]]] | None) -> dict[str, str]:
    """대상과 대조할 행의 원문 칸. 대상 이름이 그 행의 신청자 묶음 이름이면 묶음 열의 금액 칸을 행 칸 위에 둔다."""
    cells = row_values.get(row_id, {})
    if not isinstance(target, str) or not normalize_source_text(target):
        return cells
    for label, group in (target_values or {}).get(row_id, {}).items():
        if normalize_source_text(target) in {normalize_source_text(name) for name in _group_names(label)}:
            return {**cells, **group}
    return cells


def _group_names(label: str) -> list[str]:
    short = re.sub(r"\s*\(.*\)\s*$", "", label)
    return [label, short] if short else [label]
