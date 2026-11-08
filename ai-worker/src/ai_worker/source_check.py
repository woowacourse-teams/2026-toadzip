"""공급행 값을 원문 표 칸과 대조한다(원문 대조 검산).

행 근거(sourceRows)의 행 ID와 인용이 원문 표 행에서 확인되면 단지명·주택형·공급대상은 같은 의미의 원문 칸과,
수치는 같은 의미의 머리글 열과 비교한다. 확인되지 않은 값은 비우고 미확인(unresolved)과 경고로 남긴다.
"""

import re
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
                      target_values: dict[str, dict[str, dict[str, str]]] | None = None) -> list[str]:
    """공급행·대상 경로마다 근거(행 ID·인용·쪽)를 원문 표 행에서 확인하고, 확인되면 값을 같은 의미의 원문 칸과 대조한다.
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
            _check_string_source_values(path, supply_rows, row_ids, values, unresolved, target_values)
            _check_numeric_source_values(path, supply_rows, row_ids, values, unresolved, target_values)
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
                                 target_values: dict[str, dict[str, dict[str, str]]] | None) -> None:
    """표 근거가 같은 의미의 머리글 열을 지지하지 않으면 수치 값을 미확인으로 낮춘다."""
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
        if any(numeric_cell_matches(value, cell.get(field, ""), field, cell.get(f"{field}Unit"))
               for cell in cells):
            continue
        values[field] = None
        field_path = f"{path}.{field}"
        reason = "추출한 수치가 같은 의미의 원문 표 열에서 확인되지 않음"
        unresolved.append({"path": field_path, "reason": reason})


def _check_string_source_values(path: str, rows: list[dict], row_ids: set[str],
                                source_row_values: dict[str, dict[str, str]],
                                unresolved: list[dict],
                                target_values: dict[str, dict[str, dict[str, str]]] | None) -> None:
    """단지명·주택형·공급대상은 해당 의미의 원문 셀과 비교한다."""
    for values, field, source_field in _supply_string_fields_at_path(path, rows):
        value = values.get(field)
        if not isinstance(value, str) or not value:
            continue
        if any(normalize_source_text(cell) == normalize_source_text(value)
               for cell in source_texts(source_field, row_ids, source_row_values, target_values)):
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


def source_texts(field: str, row_ids, row_values: dict[str, dict[str, str]],
                 target_values: dict[str, dict[str, dict[str, str]]] | None = None) -> list[str]:
    """행 ID들의 같은 의미 원문 칸. 공급대상 머리글 아래 세부 구분 칸(소득 있음·없음)이 따로 있으면
    공급대상은 둘을 합친 이름(청년(소득 없음))이다. 금액 열을 나눈 신청자 묶음 머리글(가군(생계·의료급여수급자 등))도
    공급대상 이름이고, 괄호 설명을 뺀 이름(가군)으로도 쓴다."""
    values = [row_values.get(row_id, {}).get(field) for row_id in row_ids]
    texts = [value for value in values if isinstance(value, str) and value.strip()]
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
