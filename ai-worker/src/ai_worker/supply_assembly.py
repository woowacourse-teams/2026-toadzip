"""짧은 공급행 목록(모델 공급 단계 응답이나 코드가 표에서 묶은 행)을 관리자 계약의 공급행·단지로 조립한다.

같은 단지·주택형 행은 한 공급행의 대상으로 모으고, 값마다 그 칸이 있는 원문 표 행을 근거로 둔다.
문자열·수치는 source_check로 같은 의미의 원문 칸과 대조해 확인되지 않으면 미확인으로 남긴다.
"""

import re

from ai_worker import checks, pages
from ai_worker.checks import deduplicate
from ai_worker.source_check import (
    check_source_rows, normalize_source_text, numeric_cell_matches, source_row_map, source_texts,
)


_RESULT_FIELD = {"lhSourceRow": "sourceRow"}
_ROW_FIELDS = ("totalSupplyHouseholdCount", "exclusiveArea", "commonArea", "supplyArea", "constructedHouseholdCount")


def assemble(compact: dict, selected: pages.PromptPages) -> tuple[dict, list[str]]:
    """짧은 공급행 응답을 표 셀 근거에 맞춰 관리자 계약으로 조립한다. 원문 대조 검산의 경고를 함께 돌려준다."""
    groups: list[dict] = []
    group_indexes: dict[tuple, int] = {}
    unresolved: list[dict] = []
    compact_paths: dict[int, tuple[int, int | None]] = {}

    for item_index, item in enumerate(checks.objects(compact.get("data", {}).get("rows"))):
        unresolved_start = len(unresolved)
        row_ids = list(dict.fromkeys(value for value in item.get("rowIds", []) if isinstance(value, str))) \
            if isinstance(item.get("rowIds"), list) else []
        complex_name = _compact_source_text(item.get("complexName"), "complexName", row_ids, selected,
                                            "supplyRows[__item__].complexReference.sourceComplexName", unresolved,
                                            missing_value="")
        housing_type = _compact_source_text(item.get("housingType"), "housingType", row_ids, selected,
                                            "supplyRows[__item__].sourceHousingTypeName", unresolved,
                                            missing_value=None)
        has_target = "target" in item
        target = _compact_source_text(item.get("target"), "target", row_ids, selected,
                                      "supplyRows[__item__].targets[__target__].target", unresolved,
                                      missing_value="", required=has_target) if has_target else None

        if complex_name and housing_type:
            key = ("valid", complex_key(complex_name), complex_key(housing_type))
        else:
            key = ("unresolved", item_index)
        group_index = group_indexes.get(key)
        if group_index is None:
            group_index = len(groups)
            group_indexes[key] = group_index
            groups.append({
                "complexName": complex_name,
                "housingType": housing_type,
                "rowIds": set(),
                "items": [],
                "rowValues": {},
                "subtypes": [],
                "targets": [],
            })
        group = groups[group_index]
        target_index = len(group["targets"])
        for finding in unresolved[unresolved_start:]:
            finding["path"] = finding["path"].replace("[__item__]", f"[{group_index}]")
            finding["path"] = finding["path"].replace("[__target__]", f"[{target_index}]")
        group["rowIds"].update(row_ids)
        group["items"].append(item)
        for subtype in checks.objects(item.get("subtypes")):
            subtype_name = _compact_string(subtype.get("name"))
            if not subtype_name:
                continue
            raw_text = "".join(selected.supply_row_text.get(row_id, "") for row_id in row_ids)
            subtype_supported = normalize_source_text(subtype_name) in normalize_source_text(raw_text)
            if not subtype_supported:
                unresolved.append({"path": f"supplyRows[{group_index}].subtypes",
                                   "reason": f"세부 주택형 {subtype_name}을(를) 원문 표 행에서 확인하지 못함"})
            clean_subtype = {
                "name": subtype_name,
                "exclusiveArea": _compact_number(subtype.get("exclusiveArea"), "exclusiveArea") if subtype_supported else None,
                "commonArea": _compact_number(subtype.get("commonArea"), "commonArea") if subtype_supported else None,
                "constructedHouseholdCount": _compact_number(
                    subtype.get("constructedHouseholdCount"), "constructedHouseholdCount") if subtype_supported else None,
            }
            subtype_index = next((i for i, value in enumerate(group["subtypes"])
                                  if value["name"] == subtype_name), len(group["subtypes"]))
            subtype_rows = [selected.supply_row_values.get(row_id, {}) for row_id in row_ids
                            if normalize_source_text(selected.supply_row_values.get(row_id, {}).get("housingType", ""))
                            == normalize_source_text(subtype_name)]
            for field in ("exclusiveArea", "commonArea", "constructedHouseholdCount"):
                value = clean_subtype[field]
                if value is not None and not any(
                    numeric_cell_matches(value, source.get(field, ""), field) for source in subtype_rows
                ):
                    clean_subtype[field] = None
                    unresolved.append({"path": f"supplyRows[{group_index}].subtypes[{subtype_index}].{field}",
                                       "reason": "세부 주택형의 같은 의미 원문 표 열에서 수치를 확인하지 못함"})
            previous = next((value for value in group["subtypes"] if value["name"] == subtype_name), None)
            if previous is None:
                group["subtypes"].append(clean_subtype)
            elif previous != clean_subtype:
                group["subtypes"].remove(previous)
                unresolved.append({"path": f"supplyRows[{group_index}].subtypes",
                                   "reason": f"세부 주택형 {subtype_name} 값이 서로 달라 미확인으로 남김"})
        for field in ("totalSupplyHouseholdCount", "exclusiveArea", "commonArea", "supplyArea",
                      "constructedHouseholdCount", "expectedMoveInMonth", "lhSourceRow"):
            if item.get(field) is None:
                continue
            value = item[field]
            if field not in group["rowValues"]:
                group["rowValues"][field] = value
            elif group["rowValues"][field] != value:
                group["rowValues"][field] = None
                # 모델의 LH 행 번호(lhSourceRow)는 결과에서 sourceRow다.
                path = f"supplyRows[{group_index}].{_RESULT_FIELD.get(field, field)}"
                unresolved.append({"path": path, "reason": "같은 주택형 행에서 서로 다른 값이 나와 미확인으로 남김"})

        # 코드가 표에서 읽을 때 하나로 정하지 못한 값은 그 경로의 미확인으로 남긴다.
        findings = [finding for finding in checks.objects(item.get("unresolved"))
                    if isinstance(finding.get("field"), str) and isinstance(finding.get("reason"), str)]
        unresolved.extend({"path": f"supplyRows[{group_index}].{finding['field']}", "reason": finding["reason"]}
                          for finding in findings if finding["field"] in _ROW_FIELDS)
        if has_target:
            target_values = {
                "target": target,
                "supplyHouseholdCount": _compact_number(item.get("supplyHouseholdCount"), "supplyHouseholdCount"),
                "reserveCount": _compact_number(item.get("reserveCount"), "reserveCount"),
                "rentalDeposit": _compact_number(item.get("rentalDeposit"), "rentalDeposit"),
                "monthlyRent": _compact_number(item.get("monthlyRent"), "monthlyRent"),
                "convertedDeposit": _compact_number(item.get("convertedDeposit"), "convertedDeposit"),
                "applicationCondition": _compact_string(item.get("applicationCondition")),
                "rowIds": set(row_ids),
            }
            # reserveCount는 이번 공고의 예비자 모집수라는 제품 계약의 핵심 값이다.
            if target_values["reserveCount"] is None and target:
                unresolved.append({"path": f"supplyRows[{group_index}].targets[{target_index}].reserveCount",
                                   "reason": "이번 공고의 예비자 모집수를 표에서 확인하지 못함"})
            unresolved.extend({"path": f"supplyRows[{group_index}].targets[{target_index}].{finding['field']}",
                               "reason": finding["reason"]} for finding in findings if finding["field"] in target_values)
            group["targets"].append(target_values)
            compact_paths[item_index] = (group_index, target_index)
        else:
            compact_paths[item_index] = (group_index, None)

    supply_rows = []
    source_rows = []
    evidence = []
    for index, group in enumerate(groups):
        row_ids = sorted(group["rowIds"])
        row = {
            "complexReference": {"sourceComplexName": group["complexName"]},
            "sourceHousingTypeName": group["housingType"],
            # 모델은 LH 틀 목록 번호를 lhSourceRow로 쓴다. 결과의 sourceRow는 source_frame이 원천 행 식별자로 바꾼다.
            "sourceRow": group["rowValues"].get("lhSourceRow"),
            "expectedMoveInMonth": group["rowValues"].get("expectedMoveInMonth"),
            "totalSupplyHouseholdCount": _compact_number(group["rowValues"].get("totalSupplyHouseholdCount"),
                                                         "totalSupplyHouseholdCount"),
            "exclusiveArea": _compact_number(group["rowValues"].get("exclusiveArea"), "exclusiveArea"),
            "commonArea": _compact_number(group["rowValues"].get("commonArea"), "commonArea"),
            "supplyArea": _compact_number(group["rowValues"].get("supplyArea"), "supplyArea"),
            "constructedHouseholdCount": _compact_number(group["rowValues"].get("constructedHouseholdCount"),
                                                          "constructedHouseholdCount"),
            "subtypes": group["subtypes"],
            "targets": [],
        }
        supply_rows.append(row)
        source_rows.append({"path": f"supplyRows[{index}]", "rowIds": row_ids})
        evidence.extend(_compact_evidence(f"supplyRows[{index}]", row_ids, selected))
        evidence.extend(_value_evidence(f"supplyRows[{index}]", row, _ROW_FIELDS, row_ids, selected))
        for target_index, target_values in enumerate(group["targets"]):
            target_row_ids = sorted(target_values.pop("rowIds"))
            target = {name: target_values[name] for name in (
                "target", "supplyHouseholdCount", "reserveCount", "rentalDeposit", "monthlyRent",
                "convertedDeposit", "applicationCondition",
            )}
            row["targets"].append(target)
            path = f"supplyRows[{index}].targets[{target_index}]"
            source_rows.append({"path": path, "rowIds": target_row_ids})
            evidence.extend(_compact_evidence(path, target_row_ids, selected))
            evidence.extend(_value_evidence(path, target, _TARGET_VALUE_FIELDS, target_row_ids, selected))

    complexes, complex_row_ids, complex_unresolved = _compact_complexes(supply_rows, groups, selected)
    unresolved.extend(complex_unresolved)
    for index, row_ids in enumerate(complex_row_ids):
        evidence.extend(_compact_evidence(f"complexes[{index}]", sorted(row_ids), selected))

    source_by_path = {item["path"]: set(item["rowIds"]) for item in source_rows}
    shared_counts = []
    for shared in checks.objects(compact.get("sharedCounts")):
        field = shared.get("field")
        count = _compact_number(shared.get("count"), field) if isinstance(field, str) else None
        row_indexes = shared.get("rowIndexes")
        row_ids = list(dict.fromkeys(value for value in shared.get("rowIds", []) if isinstance(value, str))) \
            if isinstance(shared.get("rowIds"), list) else []
        paths = []
        if field in ("totalSupplyHouseholdCount", "supplyHouseholdCount", "reserveCount") and isinstance(row_indexes, list):
            for row_index in row_indexes:
                mapped = compact_paths.get(row_index) if isinstance(row_index, int) and not isinstance(row_index, bool) else None
                if mapped is None:
                    continue
                supply_index, target_index = mapped
                if field == "totalSupplyHouseholdCount":
                    paths.append(f"supplyRows[{supply_index}].{field}")
                elif field != "totalSupplyHouseholdCount" and target_index is not None:
                    paths.append(f"supplyRows[{supply_index}].targets[{target_index}].{field}")
        paths = list(dict.fromkeys(paths))
        field_supported = count is not None and any(
            numeric_cell_matches(count, selected.supply_row_values.get(row_id, {}).get(field, ""), field)
            for row_id in row_ids
        )
        if len(paths) < 2 or not field_supported:
            unresolved.extend({"path": path, "reason": "공통 수량을 같은 의미의 원문 표 열에서 확인하지 못함"}
                               for path in paths)
            continue
        page_match = next((re.match(r"P(\d+):T\d+:R\d+", row_id) for row_id in row_ids
                           if re.match(r"P(\d+):T\d+:R\d+", row_id)), None)
        page = int(page_match.group(1)) if page_match else None
        quote_ids = [row_id for row_id in row_ids if re.match(rf"P{page}:T\d+:R\d+", row_id)] if page else []
        quote = " ".join(selected.supply_row_text.get(row_id, "").strip() for row_id in quote_ids)[:500]
        if page is None or not quote:
            unresolved.extend({"path": path, "reason": "공통 수량의 원문 쪽과 인용을 만들지 못함"} for path in paths)
            continue
        shared_counts.append({"paths": paths, "count": count, "page": page, "quote": quote})
        for path in paths:
            source_by_path.setdefault(path, set()).update(row_ids)
            unresolved = [item for item in unresolved if not (
                item.get("path") == path and item.get("reason") == "이번 공고의 예비자 모집수를 표에서 확인하지 못함"
            )]
    source_rows = [{"path": path, "rowIds": sorted(row_ids)} for path, row_ids in source_by_path.items()]

    tree = {
        "data": {
            "complexes": complexes,
            "supplyRows": supply_rows,
            "unresolvedFields": deduplicate(unresolved),
        },
        "sharedCounts": shared_counts,
        "notApplicable": [],
        "evidence": evidence,
        "sourceRows": source_rows,
    }
    # 문자열·수치는 동일 의미의 머리글 열에서 확인할 수 있을 때만 유지한다.
    row_source_map = source_row_map(source_rows)
    warnings = check_source_rows(supply_rows, row_source_map, selected.supply_row_text, evidence,
                                 tree["data"]["unresolvedFields"], selected.supply_row_values)
    tree["data"]["unresolvedFields"] = deduplicate(tree["data"]["unresolvedFields"])
    return tree, warnings


def _compact_source_text(candidate: object, field: str, row_ids: list[str], selected: pages.PromptPages,
                         path: str, unresolved: list[dict], missing_value: str | None,
                         required: bool = True) -> str | None:
    source_values = source_texts(field, row_ids, selected.supply_row_values)
    if isinstance(candidate, str) and candidate.strip():
        matched = next((value for value in source_values
                        if normalize_source_text(value) == normalize_source_text(candidate)), None)
        if matched is not None:
            return _normalize_source_display(matched, field)
        unresolved.append({"path": path, "reason": "추출한 값이 같은 의미의 원문 표 열에서 확인되지 않음"})
        return missing_value
    if len(source_values) == 1:
        return _normalize_source_display(source_values[0], field)
    if required:
        reason = "원문 표 행 ID가 없거나 해당 열의 값을 확인하지 못함"
        unresolved.append({"path": path, "reason": reason})
    elif len(source_values) > 1:
        unresolved.append({"path": path, "reason": "여러 원문 표 값이 있어 하나로 결정하지 못함"})
    return missing_value


def _compact_number(value: object, field: str) -> int | float | None:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return None
    if field in ("exclusiveArea", "commonArea", "supplyArea"):
        return value
    if isinstance(value, int) or value.is_integer():
        return int(value)
    return None


def _compact_string(value: object) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None


def _compact_complexes(supply_rows: list[dict], groups: list[dict], selected: pages.PromptPages):
    names = []
    for row in supply_rows:
        name = row["complexReference"]["sourceComplexName"]
        add_complex_name(names, name)
    complexes, complex_row_ids, unresolved = [], [], []
    for name in names:
        row_ids = set()
        values = {"totalHouseholdCount": set(), "parkingCount": set()}
        addresses: dict[str, str] = {}
        for index, group in enumerate(groups):
            if complex_key(group["complexName"]) != complex_key(name):
                continue
            row_ids.update(group["rowIds"])
            for row_id in group["rowIds"]:
                cells = selected.supply_row_values.get(row_id, {})
                for field in values:
                    parsed = _integer_cell(cells.get(field))
                    if parsed is not None:
                        values[field].add(parsed)
                address = normalize_address(cells.get("address"))
                if address:
                    addresses.setdefault(address_key(address), address)
        record = {"sourceComplexName": name, "address": next(iter(addresses.values())) if len(addresses) == 1 else None}
        if len(addresses) > 1:
            unresolved.append({"path": f"complexes[{len(complexes)}].address",
                               "reason": "공급 표의 같은 단지 소재지가 서로 달라 미확인으로 남김"})
        for field, candidates in values.items():
            record[field] = next(iter(candidates)) if len(candidates) == 1 else None
            if len(candidates) > 1:
                unresolved.append({"path": f"complexes[{len(complexes)}].{field}",
                                   "reason": "공급 표의 같은 단지 값이 서로 달라 미확인으로 남김"})
        complexes.append(record)
        complex_row_ids.append(row_ids)
    return complexes, complex_row_ids, unresolved


def _integer_cell(value: object) -> int | None:
    if not isinstance(value, str):
        return None
    number = re.sub(r"[^\d-]", "", value.replace(",", ""))
    try:
        return int(number) if number and number != "-" else None
    except ValueError:
        return None


_TARGET_VALUE_FIELDS = ("supplyHouseholdCount", "reserveCount", "rentalDeposit", "monthlyRent", "convertedDeposit")


def _value_evidence(path: str, values: dict, fields: tuple[str, ...], row_ids: list[str],
                    selected: pages.PromptPages) -> list[dict]:
    """공급행·대상 근거는 여러 쪽(공급표·임대조건표)에 걸칠 수 있다. 화면은 같은 경로의 첫 근거를 쓰므로,
    값마다 그 칸이 적힌 행만 가리키는 근거를 따로 둔다(보증금 근거가 공급표 쪽을 가리키지 않게)."""
    if len({row_id.split(":")[0] for row_id in row_ids}) <= 1:
        return []  # 근거가 한 쪽뿐이면 상위 근거가 이미 그 쪽을 가리킨다. 근거 개수 상한(화면 5000개)을 아낀다.
    result = []
    for field in fields:
        if values.get(field) is None:
            continue
        cells = {row_id: selected.supply_row_values.get(row_id, {}) for row_id in row_ids}
        ids = [row_id for row_id, cell in cells.items() if cell.get(field)]
        # 이름을 이어받으려고 넣은 다른 줄(예: 소득 없음 줄)도 같은 열에 값이 있어, 값이 실제로 맞는 줄을 먼저 쓴다.
        matching = [row_id for row_id in ids if numeric_cell_matches(values[field], cells[row_id][field], field,
                                                                      cells[row_id].get(f"{field}Unit"))]
        result.extend(_compact_evidence(f"{path}.{field}", matching or ids, selected)[:1])
    return result


def _compact_evidence(path: str, row_ids: list[str], selected: pages.PromptPages) -> list[dict]:
    grouped: dict[int, list[str]] = {}
    for row_id in row_ids:
        match = re.match(r"P(\d+):T\d+:R\d+", row_id)
        if not match:
            continue
        source_page = int(match.group(1))
        if source_page not in selected.supply_pages:
            continue
        grouped.setdefault(source_page, []).append(row_id)
    return [
        {"path": path, "page": page, "quote": " ".join(
            selected.supply_row_text[row_id].strip() for row_id in ids if selected.supply_row_text.get(row_id)
        )[:500]}
        for page, ids in grouped.items()
    ]


def _normalize_source_display(value: str, field: str) -> str:
    """PDF 줄바꿈 때문에 대상 이름 괄호 주변에 생긴 추출 공백만 정리한다."""
    if field != "target":
        return value
    value = re.sub(r"\s+", " ", value).strip()
    value = re.sub(r"\s+\(", "(", value)
    value = re.sub(r"\(\s+", "(", value)
    return re.sub(r"\s+\)", ")", value)


def add_complex_name(names: list[str], name: object) -> None:
    if isinstance(name, str) and name and all(complex_key(existing) != complex_key(name) for existing in names):
        names.append(name)


def complex_key(name: str) -> str:
    return re.sub(r"\s+", "", name)


# 공고문은 시·도를 줄여 쓰거나 옛 이름으로 쓴다. 주소 검색 결과의 정식 이름과 같은 주소로 본다.
_PROVINCE_ALIASES = (
    ("서울특별시", ("서울시", "서울")), ("부산광역시", ("부산시", "부산")), ("대구광역시", ("대구시", "대구")),
    ("인천광역시", ("인천시", "인천")), ("광주광역시", ("광주시",)), ("대전광역시", ("대전시", "대전")),
    ("울산광역시", ("울산시", "울산")), ("세종특별자치시", ("세종시", "세종")), ("경기도", ("경기",)),
    ("강원특별자치도", ("강원도", "강원")), ("충청북도", ("충북",)), ("충청남도", ("충남",)),
    ("전북특별자치도", ("전라북도", "전북")), ("전라남도", ("전남",)), ("경상북도", ("경북",)),
    ("경상남도", ("경남",)), ("제주특별자치도", ("제주도",)),
)


def normalize_address(value: object) -> str | None:
    """표 칸의 단지 소재지를 대표 주소 하나로 줄인다. 괄호 속 동·단지명, "일원", "외 N필지", 둘째 번지부터는 뺀다."""
    if not isinstance(value, str):
        return None
    text = re.sub(r"\s+", " ", value).strip()
    # 칸 너비에서 잘려 닫는 괄호가 없는 꼬리도 괄호 속 동·단지명으로 본다.
    text = re.sub(r"\([^)]*(?:\)|$)", " ", text)
    text = re.sub(r"(\d)\s*[,·]\s*\d.*$", r"\1", text)
    text = re.sub(r"\s*외\s*\d+\s*필지.*$|\s*일원.*$", "", text)
    text = re.sub(r"(\d)\s*번지", r"\1", text)
    text = re.sub(r"\s+", " ", text).strip()
    # 도로명+건물번호나 동·리+번지가 없으면 단지 위치로 검색할 수 없는 지구·블록명이다.
    return text if re.search(r"[시도군구]\s", text + " ") and re.search(r"(?:로|길|동|리|가)\s*(?:산\s*)?\d", text) else None


def address_key(address: str) -> str:
    """같은 주소인지 비교하는 열쇠. 공백·괄호를 지우고 시·도 약칭을 정식 이름으로 맞춘다."""
    text = re.sub(r"\([^)]*\)|\s+", "", address)
    for official, aliases in _PROVINCE_ALIASES:
        if text.startswith(official):
            return text
        for alias in aliases:
            if text.startswith(alias) and not text.startswith(official):
                return official + text[len(alias):]
    return text
