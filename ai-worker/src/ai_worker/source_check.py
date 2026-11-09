"""공급행 값을 원문 표 칸과 대조한다(원문 대조 검산).

행 근거(sourceRows)의 행 ID와 인용이 원문 표 행에서 확인되면 단지명·주택형·공급대상은 같은 의미의 원문 칸과,
수치는 같은 의미의 머리글 열과 비교한다. 확인되지 않은 값은 비우고 미확인(unresolved)과 경고로 남긴다.
"""

import re
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation

from ai_worker import checks


def source_row_map(source_rows: object) -> dict[str, set[str]]:
    result: dict[str, set[str]] = {}
    for item in checks.objects(source_rows):
        path, row_ids = item.get("path"), item.get("rowIds")
        if not isinstance(path, str) or not isinstance(row_ids, list):
            continue
        result.setdefault(path, set()).update(row_id for row_id in row_ids if isinstance(row_id, str))
    return result


def check_source_rows(supply_rows: list[dict], source_rows: dict[str, set[str]], source_row_text: dict[str, str],
                      evidence: list[dict], unresolved: list[dict],
                      source_row_values: dict[str, dict[str, str]] | None = None,
                      target_values: dict[str, dict[str, dict[str, str]]] | None = None,
                      block_counts: dict[str, dict[str, int]] | None = None,
                      headings: "Headings | None" = None) -> list[str]:
    """headings는 단지명 열이 없는 표의 단지명을 표 위 가장 가까운 제목과 대조할 때 쓴다(Headings).
    block_counts는 주택타입 여럿을 묶은 주택형 공급행의 경로별 수량 합이다. 그 수량은 행 칸 대신 이 합과 대조한다.
    공급행·대상 경로마다 근거(행 ID·인용·쪽)를 원문 표 행에서 확인하고, 확인되면 값을 같은 의미의 원문 칸과 대조한다.
    target_values는 금액 열이 신청자 묶음(가군·나군) 머리글로 나뉜 표의 행별 묶음 칸이다(target_cells).
    확인하지 못한 값은 비우고 unresolved에 사유를 남긴다. 경고는 근거를 확인하지 못한 경로마다 한 줄이고,
    값마다의 사유는 unresolved에만 있다."""
    warnings = []
    expected = []
    for index, row in enumerate(supply_rows):
        path = f"supplyRows[{index}]"
        expected.append(path)
        for target_index, _ in enumerate(checks.objects(row.get("targets"))):
            expected.append(f"{path}.targets[{target_index}]")
    for path in expected:
        row_ids = source_rows.get(path, set())
        unknown = row_ids - source_row_text.keys()
        matching_evidence = next((item for item in evidence if item.get("path") == path), None)
        quote = matching_evidence.get("quote") if isinstance(matching_evidence, dict) else None
        quote_text = re.sub(r"\s+", "", quote) if isinstance(quote, str) else ""
        cited_rows = "".join(source_row_text[row_id] for row_id in row_ids if row_id in source_row_text)
        cited_text = re.sub(r"\s+", "", cited_rows)
        source_pages = {int(match.group(1)) for row_id in row_ids
                        if (match := re.match(r"P(\d+):T\d+:R\d+", row_id))}
        evidence_page = matching_evidence.get("page") if isinstance(matching_evidence, dict) else None
        supported = bool(row_ids) and not unknown and bool(quote_text) and all(
            token in cited_text for token in re.split(r"\s+", quote) if token
        )
        if supported and (not source_pages or evidence_page in source_pages):
            values = source_row_values or {}
            _check_string_source_values(path, supply_rows, row_ids, values, unresolved, target_values, headings)
            _check_numeric_source_values(path, supply_rows, row_ids, values, unresolved, target_values,
                                         (block_counts or {}).get(path, {}))
            continue
        if not row_ids:
            reason = "원문 표 행 ID를 확인하지 못함"
        elif unknown:
            reason = "응답의 원문 표 행 ID가 입력 표와 일치하지 않음"
        elif not matching_evidence or not quote_text or not all(
            token in cited_text for token in re.split(r"\s+", quote) if token
        ):
            reason = "근거 인용을 지정한 원문 표 행에서 확인하지 못함"
        else:
            reason = "근거 쪽과 원문 표 행 ID의 쪽이 일치하지 않음"
        unresolved.append({"path": path, "reason": reason})
        warnings.append(f"{path}: {reason}")
        _clear_unverified_string_values(path, supply_rows, unresolved, reason)
        _clear_unverified_numeric_values(path, supply_rows, unresolved, reason)
    return warnings


def _check_numeric_source_values(path: str, rows: list[dict], row_ids: set[str],
                                 source_row_values: dict[str, dict[str, str]],
                                 unresolved: list[dict],
                                 target_values: dict[str, dict[str, dict[str, str]]] | None,
                                 block_counts: dict[str, int]) -> None:
    """표 근거가 같은 의미의 머리글 열을 지지하지 않으면 수치 값을 미확인으로 낮춘다.
    주택형 공급행의 수량 합(block_counts)이 있는 필드는 그 합과 같아야 한다. 주택타입 한 행의 수는 그 공급행의 수가 아니다."""
    match = re.fullmatch(r"supplyRows\[(\d+)\](?:\.targets\[(\d+)\])?", path)
    if not match:
        return
    values, numeric_fields = _supply_values_at_path(path, rows)
    if values is None:
        return
    for field in numeric_fields:
        value = values.get(field)
        if value is None:
            continue
        cells = [target_cells(values.get("target"), row_id, source_row_values, target_values) for row_id in row_ids]
        if field in block_counts:
            # 주택형 공급행의 수량은 주택타입 행 수의 합이다. 합을 정하지 못했으면(None) 어떤 값도 확인하지 못한다.
            if block_counts[field] is not None and value == block_counts[field]:
                continue
        elif any(source_cell_matches(value, cell, field) for cell in cells):
            continue
        values[field] = None
        field_path = f"{path}.{field}"
        reason = "추출한 수치가 같은 의미의 원문 표 열에서 확인되지 않음"
        unresolved.append({"path": field_path, "reason": reason})


def _check_string_source_values(path: str, rows: list[dict], row_ids: set[str],
                                source_row_values: dict[str, dict[str, str]],
                                unresolved: list[dict],
                                target_values: dict[str, dict[str, dict[str, str]]] | None,
                                headings: "Headings | None") -> None:
    """단지명·주택형·공급대상은 해당 의미의 원문 셀과 비교한다. 단지명 칸이 없는 행의 단지명은 표 위 가장 가까운 제목과 비교한다."""
    for values, field, source_field in _supply_string_fields_at_path(path, rows):
        value = values.get(field)
        if not isinstance(value, str) or not value:
            continue
        if any(normalize_source_text(cell) == normalize_source_text(value)
               for cell in source_texts(source_field, row_ids, source_row_values, target_values)):
            continue
        if source_field == "complexName" and headings is not None and headings.confirms(value, row_ids, source_row_values):
            continue
        values[field] = None if field == "sourceHousingTypeName" else ""
        field_path = f"{path}.complexReference.sourceComplexName" if field == "sourceComplexName" else f"{path}.{field}"
        reason = "추출한 값이 같은 의미의 원문 표 열에서 확인되지 않음"
        unresolved.append({"path": field_path, "reason": reason})


def _clear_unverified_string_values(path: str, rows: list[dict], unresolved: list[dict],
                                    reason: str) -> None:
    for values, field, _ in _supply_string_fields_at_path(path, rows):
        value = values.get(field)
        if not isinstance(value, str) or not value:
            continue
        values[field] = None if field == "sourceHousingTypeName" else ""
        field_path = f"{path}.complexReference.sourceComplexName" if field == "sourceComplexName" else f"{path}.{field}"
        field_reason = f"{reason}; 문자열 원문을 확인하지 못함"
        unresolved.append({"path": field_path, "reason": field_reason})


def _supply_string_fields_at_path(path: str, rows: list[dict]) -> list[tuple[dict, str, str]]:
    match = re.fullmatch(r"supplyRows\[(\d+)\](?:\.targets\[(\d+)\])?", path)
    if not match:
        return []
    row_index = int(match.group(1))
    if row_index >= len(rows):
        return []
    row = rows[row_index]
    if match.group(2) is None:
        result = [(row, "sourceHousingTypeName", "housingType")]
        reference = row.get("complexReference")
        if isinstance(reference, dict):
            result.append((reference, "sourceComplexName", "complexName"))
        return result
    target_index = int(match.group(2))
    targets = checks.objects(row.get("targets"))
    if target_index >= len(targets):
        return []
    return [(targets[target_index], "target", "target")]


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


def _clear_unverified_numeric_values(path: str, rows: list[dict], unresolved: list[dict],
                                     reason: str) -> None:
    values, fields = _supply_values_at_path(path, rows)
    if values is None:
        return
    for field in fields:
        if values.get(field) is None:
            continue
        values[field] = None
        field_path = f"{path}.{field}"
        field_reason = f"{reason}; 수치 원문을 확인하지 못함"
        unresolved.append({"path": field_path, "reason": field_reason})


def _supply_values_at_path(path: str, rows: list[dict]) -> tuple[dict | None, tuple[str, ...]]:
    match = re.fullmatch(r"supplyRows\[(\d+)\](?:\.targets\[(\d+)\])?", path)
    if not match:
        return None, ()
    row_index = int(match.group(1))
    if row_index >= len(rows):
        return None, ()
    row = rows[row_index]
    if match.group(2) is None:
        return row, ("totalSupplyHouseholdCount", "exclusiveArea", "commonArea", "supplyArea",
                     "constructedHouseholdCount")
    target_index = int(match.group(2))
    targets = checks.objects(row.get("targets"))
    if target_index >= len(targets):
        return None, ()
    return targets[target_index], ("supplyHouseholdCount", "reserveCount", "rentalDeposit", "monthlyRent",
                                    "convertedDeposit")


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
    winners, reserve = count_cell(cells.get("supplyHouseholdCount")), count_cell(cells.get("reserveCount"))
    candidates = [cells.get("totalSupplyHouseholdCount", "")]
    if winners is not None:
        candidates.append(str(winners + (reserve or 0)))
    return any(numeric_cell_matches(value, cell, field) for cell in candidates)


def recruitment_count(cells: dict[str, str]) -> int | None:
    """한 원문 행의 이번 공고 모집수. 모집호수 칸(계)이 있으면 그 값, 없으면 당첨자 칸과 예비자 칸의 합이다(source_cell_matches와 같은 규칙)."""
    total = count_cell(cells.get("totalSupplyHouseholdCount"))
    if total is not None:
        return total
    winners = count_cell(cells.get("supplyHouseholdCount"))
    return None if winners is None else winners + (count_cell(cells.get("reserveCount")) or 0)


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
