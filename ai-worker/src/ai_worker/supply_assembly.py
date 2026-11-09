"""짧은 공급행 목록(모델 공급 단계 응답이나 코드가 표에서 묶은 행)을 관리자 계약의 공급행·단지로 조립한다.

같은 단지·주택형 행은 한 공급행의 대상으로 모으고, 값마다 그 칸이 있는 원문 표 행을 근거로 둔다.
문자열·수치는 source_check로 같은 의미의 원문 칸과 대조해 확인되지 않으면 미확인으로 남긴다.
"""

import re
from dataclasses import dataclass

from ai_worker import checks, pages, tables
from ai_worker.checks import deduplicate
from ai_worker.source_check import (
    Headings, check_source_rows, count_cell, normalize_source_text, numeric_cell_matches, recruitment_count,
    source_cell_matches, source_row_map, source_texts, target_cells,
)


_ROW_FIELDS = ("totalSupplyHouseholdCount", "exclusiveArea", "commonArea", "supplyArea", "constructedHouseholdCount")


def assemble(compact: dict, selected: pages.PromptPages, source_names=()) -> tuple[dict, list[str]]:
    """짧은 공급행 응답을 표 셀 근거에 맞춰 관리자 계약으로 조립한다. 원문 대조 검산의 경고를 함께 돌려준다.
    source_names는 LH 공급행의 단지명이다. 표 위 블록 제목과 단지명을 대조할 때 후보로 쓴다(Headings)."""
    groups: list[dict] = []
    group_indexes: dict[tuple, int] = {}
    unresolved: list[dict] = []
    compact_paths: dict[int, tuple[int, int | None]] = {}
    items = checks.objects(compact.get("data", {}).get("rows"))
    per_house_type_tables = _per_house_type_tables(items, selected)
    headings = Headings(selected.supply_row_headings, tuple(
        name for name in (*(item.get("complexName") for item in items), *source_names) if isinstance(name, str)))

    for item_index, item in enumerate(items):
        unresolved_start = len(unresolved)
        row_ids = _row_ids(item)
        complex_name = None
        if headings.confirms(item.get("complexName"), row_ids, selected.supply_row_values):
            complex_name = _normalize_source_display(item["complexName"], "complexName")
        if complex_name is None:
            complex_name = _compact_source_text(item.get("complexName"), "complexName", row_ids, selected,
                                                "supplyRows[__item__].complexReference.sourceComplexName", unresolved,
                                                missing_value="")
        unit = _house_type_unit(row_ids, selected, per_house_type_tables)
        if unit is None:
            housing_type = _compact_source_text(item.get("housingType"), "housingType", row_ids, selected,
                                                "supplyRows[__item__].sourceHousingTypeName", unresolved,
                                                missing_value=None)
        else:
            housing_type = unit.label(item.get("housingType"), "supplyRows[__item__].sourceHousingTypeName", unresolved)
        # 대상 열이 없는 표에서 모델이 target을 빠뜨려도 대상 값(예비자 수·보증금)이 있으면 대상 하나다. 버리면 그 값이 사라진다.
        has_target = "target" in item or any(item.get(field) is not None
                                             for field in (*_TARGET_VALUE_FIELDS, "applicationCondition"))
        target = _compact_source_text(item.get("target"), "target", row_ids, selected,
                                      "supplyRows[__item__].targets[__target__].target", unresolved,
                                      missing_value="", required=has_target) if has_target else None

        # 공급행은 단지·주택형 하나다. 원문에서 확인하지 못한 이름도 같은 원문 칸이나 같은 표기면 한 행으로 모은다.
        # 행마다 따로 두면 같은 표 행이 대상 수만큼 공급행으로 중복된다(사천벌리1에서 LH 행마다 같은 행이 나온 경우).
        group_complex = _group_name(complex_name, item.get("complexName"), "complexName", row_ids, selected) or ""
        group_type = _group_name(housing_type, item.get("housingType"), "housingType", row_ids, selected)
        if group_type:
            key = ("valid", complex_key(group_complex), complex_key(group_type))
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
                "tableSubtypes": [],
                "targets": [],
            })
        group = groups[group_index]
        target_index = len(group["targets"])
        for finding in unresolved[unresolved_start:]:
            finding["path"] = finding["path"].replace("[__item__]", f"[{group_index}]")
            finding["path"] = finding["path"].replace("[__target__]", f"[{target_index}]")
        group["rowIds"].update(row_ids)
        group["items"].append(item)
        table_subtypes = _table_subtypes(row_ids, housing_type, selected)
        group["tableSubtypes"].extend(table_subtypes)
        for subtype in [] if table_subtypes else checks.objects(item.get("subtypes")):
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
            subtype_rows = [selected.supply_row_values.get(row_id, {}) for row_id in row_ids
                            if normalize_source_text(selected.supply_row_values.get(row_id, {}).get("housingType", ""))
                            == normalize_source_text(subtype_name)]
            labeled = _labeled_areas(row_ids, selected)
            label = _subtype_label(subtype_name, housing_type)
            if not subtype_rows and label in labeled:
                # 공급표가 세부 주택형 면적을 한 칸에 "(A)36.02 (B)36.19"로 적은 행이다. 그 칸의 표시로 면적을 확인한다.
                if clean_subtype["exclusiveArea"] is None:
                    clean_subtype["exclusiveArea"] = labeled[label]
                subtype_rows = [{"exclusiveArea": str(labeled[label])}]
                # 모델이 세부 주택형을 A·B로만 적으면 행 형명의 숫자를 붙여 단지 주택형 이름(36A)으로 쓴다.
                if re.fullmatch(r"[A-Za-z]", subtype_name):
                    base = re.match(r"\s*(\d+)", housing_type or "")
                    if base:
                        subtype_name = clean_subtype["name"] = base.group(1) + subtype_name.upper()
            subtype_index = next((i for i, value in enumerate(group["subtypes"])
                                  if value["name"] == subtype_name), len(group["subtypes"]))
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
                      "constructedHouseholdCount", "expectedMoveInMonth"):
            if item.get(field) is None:
                continue
            value = item[field]
            if field not in group["rowValues"]:
                group["rowValues"][field] = value
            elif group["rowValues"][field] != value:
                group["rowValues"][field] = None
                unresolved.append({"path": f"supplyRows[{group_index}].{field}",
                                   "reason": "같은 주택형 행에서 서로 다른 값이 나와 미확인으로 남김"})

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
            # 모델이 같은 표 행을 두 번 쓰면 값이 모두 같은 대상이 겹친다. 하나로 두고 원문 행만 합친다.
            # 원문에서 확인하지 못해 비운 대상명은 모델이 쓴 이름(가군·나군)이 다르면 다른 대상이다.
            identity = (_compact_string(item.get("target")),
                        tuple(sorted((key, str(value)) for key, value in target_values.items() if key != "rowIds")))
            identities = group.setdefault("targetIdentities", [])
            same = identities.index(identity) if identity in identities else None
            # 같은 표 행을 반복한 경우만 합친다. 원문 행이 다르면 이름·값이 같아도 다른 대상(다른 동·층)일 수 있다.
            if same is not None and not group["targets"][same]["rowIds"] & target_values["rowIds"]:
                same = None
            if same is not None:
                group["targets"][same]["rowIds"].update(target_values["rowIds"])
                unresolved[unresolved_start:] = [finding for finding in unresolved[unresolved_start:]
                                                  if f".targets[{target_index}]" not in finding["path"]]
                compact_paths[item_index] = (group_index, same)
                continue
            group["targets"].append(target_values)
            identities.append(identity)
            compact_paths[item_index] = (group_index, target_index)
        else:
            compact_paths[item_index] = (group_index, None)

    for group_index, group in enumerate(groups):
        _area_from_source_cells(group, selected)
        _counts_from_source_cells(group_index, group, groups, selected, unresolved, per_house_type_tables)

    supply_rows = []
    source_rows = []
    evidence = []
    for index, group in enumerate(groups):
        row_ids = sorted(group["rowIds"])
        row = {
            "complexReference": {"sourceComplexName": group["complexName"]},
            "sourceHousingTypeName": group["housingType"],
            # 원천 행 식별자. GH 공급행 틀이 있으면 source_frame이 채운다. LH 행은 공급행에 대응하지 않는 참고 자료다.
            "sourceRow": None,
            "expectedMoveInMonth": group["rowValues"].get("expectedMoveInMonth"),
            "totalSupplyHouseholdCount": _compact_number(group["rowValues"].get("totalSupplyHouseholdCount"),
                                                         "totalSupplyHouseholdCount"),
            "exclusiveArea": _compact_number(group["rowValues"].get("exclusiveArea"), "exclusiveArea"),
            "commonArea": _compact_number(group["rowValues"].get("commonArea"), "commonArea"),
            "supplyArea": _compact_number(group["rowValues"].get("supplyArea"), "supplyArea"),
            "constructedHouseholdCount": _compact_number(group["rowValues"].get("constructedHouseholdCount"),
                                                          "constructedHouseholdCount"),
            "subtypes": _subtype_values(group["tableSubtypes"]) or group["subtypes"],
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
            source_cell_matches(count, selected.supply_row_values.get(row_id, {}), field) for row_id in row_ids
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
    # 주택타입 행마다 적힌 모집수의 합은 원문 칸 하나에는 없지만 그 행들에서 확인한 값이다.
    block_counts = {}
    for index, group in enumerate(groups):
        counts = group.get("blockCounts", {})
        block_counts[f"supplyRows[{index}]"] = {key: value for key, value in counts.items()
                                                 if key == "totalSupplyHouseholdCount"}
        if len(group["targets"]) == 1:
            block_counts[f"supplyRows[{index}].targets[0]"] = {key: value for key, value in counts.items()
                                                                if key != "totalSupplyHouseholdCount"}
        elif counts:
            # 대상이 여럿이면 주택타입 행 합을 대상별로 나눌 근거가 없다. 한 타입 칸(41)과 맞아도 대상 수량으로 확인하지 않는다.
            for target_index in range(len(group["targets"])):
                block_counts[f"supplyRows[{index}].targets[{target_index}]"] = {
                    "supplyHouseholdCount": None, "reserveCount": None}
    warnings = check_source_rows(supply_rows, row_source_map, selected.supply_row_text, evidence,
                                 tree["data"]["unresolvedFields"], selected.supply_row_values,
                                 selected.supply_row_target_values, block_counts, headings)
    tree["data"]["unresolvedFields"] = deduplicate(tree["data"]["unresolvedFields"])
    for index, row in enumerate(supply_rows):
        _all_applicants_when_no_target_column(index, row, source_by_path, selected, tree["data"]["unresolvedFields"])
    return tree, warnings


def _row_ids(item: dict) -> list[str]:
    values = item.get("rowIds")
    return list(dict.fromkeys(value for value in values if isinstance(value, str))) if isinstance(values, list) else []


@dataclass(frozen=True)
class _HouseTypeRow:
    """주택형(신청형) 열과 주택타입 열이 따로 있는 공급표의 주택타입 행 하나. start는 그 주택형 칸이 시작하는 행이다."""

    table: str
    row_id: str
    start: str
    housing_type: str
    house_type: str
    # 그 주택형 칸이 걸친 주택타입 수
    block_size: int

    @property
    def splits_block(self) -> bool:
        """이 행 하나만 가리키면 주택타입 단위 공급행이 분명하다. 주택형 칸이 시작하는 행이나 주택타입이 하나뿐인 주택형은
        주택형 단위 공급행도 그 행만 가리킬 수 있어 표 안 다른 공급행의 단위를 따른다."""
        return self.block_size > 1 and self.start != self.row_id


def _house_type_row(row_id: str, selected: pages.PromptPages) -> _HouseTypeRow | None:
    house_type = selected.supply_row_values.get(row_id, {}).get("houseType")
    match = re.fullmatch(r"(P\d+:T\d+):R(\d+)", row_id)
    if not house_type or not house_type.strip() or not match:
        return None
    table, number = match.group(1), int(match.group(2))
    # 주택형 칸은 아래 주택타입 행으로 병합돼 그 행들에는 값이 없다. 위로 올라가 값이 적힌 첫 행이 그 주택형 칸의 시작이다.
    for above in range(number, 0, -1):
        start = f"{table}:R{above}"
        housing_type = selected.supply_row_values.get(start, {}).get("housingType")
        if not housing_type:
            continue
        # 표가 그 주택형 칸에 묶은 주택타입(tables.TableSubtype)에 이 행이 있어야 같은 칸이다. 칸이 끝난 뒤의 행이 아니다.
        names = [name for subtype in selected.supply_row_subtypes.get(start, ()) for name in subtype.name.split("·")]
        if normalize_source_text(house_type) not in {normalize_source_text(name) for name in names}:
            return None
        return _HouseTypeRow(table, row_id, start, housing_type, house_type, len(names))
    return None


@dataclass(frozen=True)
class _HouseTypeUnit:
    """공급행 하나가 가리키는 주택형 칸 안의 주택타입 행들과, 그 행 단위에 맞는 표기(label)."""

    rows: tuple[_HouseTypeRow, ...]
    per_house_type: bool

    def label(self, candidate: object, path: str, unresolved: list[dict]) -> str | None:
        """주택타입마다 뽑은 공급행은 주택타입, 주택형마다 뽑은 공급행은 주택형 표기다(사용자 결정). 모델이 다른 열(주택형·주택타입)의
        값을 썼어도 같은 행의 원문 칸이면 행 단위에 맞는 열의 값으로 바꾼다. 어느 칸에도 없는 값은 미확인이다."""
        wanted = self.rows[0].house_type if self.per_house_type else self.rows[0].housing_type
        accepted = {normalize_source_text(value) for row in self.rows for value in (row.housing_type, row.house_type)}
        if isinstance(candidate, str) and candidate.strip() and normalize_source_text(candidate) not in accepted:
            unresolved.append({"path": path, "reason": "추출한 값이 같은 의미의 원문 표 열에서 확인되지 않음"})
            return None
        return wanted


def _house_type_unit(row_ids: list[str], selected: pages.PromptPages,
                     per_house_type_tables: set[str]) -> _HouseTypeUnit | None:
    """공급행이 주택형 칸 하나 안의 주택타입 행만 가리키면 그 행 단위. 주택타입 행 하나면 주택타입 단위이고, 여럿이면 주택형 단위다.
    주택형 칸이 시작하는 행이나 주택타입이 하나뿐인 주택형(72 → 72A)은 어느 단위인지 그 행만으로 알 수 없어,
    같은 표에서 주택타입 단위로 뽑은 다른 공급행이 있으면 주택타입 단위로 본다."""
    rows = tuple(row for row_id in row_ids if (row := _house_type_row(row_id, selected)))
    if not rows or len({row.start for row in rows}) != 1:
        return None
    single = len({normalize_source_text(row.house_type) for row in rows}) == 1
    return _HouseTypeUnit(rows, single and (rows[0].splits_block or rows[0].table in per_house_type_tables))


def _per_house_type_tables(items: list[dict], selected: pages.PromptPages) -> set[str]:
    """주택타입 행 하나만 분명히 가리키는 공급행이 있는 표. 그 표의 공급행은 주택타입마다 뽑은 것이다."""
    tables_ = set()
    for item in items:
        unit = _house_type_unit(_row_ids(item), selected, set())
        if unit is not None and unit.per_house_type:
            tables_.add(unit.rows[0].table)
    return tables_


def _counts_from_source_cells(index: int, group: dict, groups: list[dict], selected: pages.PromptPages,
                              unresolved: list[dict], per_house_type_tables: set[str]) -> None:
    """모델이 모집수·예비자 수를 빠뜨린 공급행은 그 행이 가리키는 원문 표의 수량 행이 하나로 정해지면 그 칸을 쓴다.
    모집수는 입주자와 예비자를 합한 수다(사용자 결정). 모집호수 칸(입주자·예비자를 나눈 표의 '계')이 있으면 그 값,
    없으면 당첨자 칸과 예비자 칸의 합이다. 다른 공급행도 가리키거나 이 공급행 밖의 행까지 병합된 수량 칸은 여러 공급행의
    공통 수량이라 채우지 않는다(나누거나 복제하지 않는다). 모델이 낸 값은 바꾸지 않고 뒤의 원문 대조에 맡긴다.
    주택타입이 여럿인 주택형을 한 공급행으로 뽑았으면 수량은 주택타입 행마다라 그 합이 공급행 모집수다(_block_counts)."""
    unit = _house_type_unit(sorted(group["rowIds"]), selected, per_house_type_tables)
    if unit is not None and not unit.per_house_type and unit.rows[0].block_size > 1:
        _fill_block_counts(index, group, groups, unit, selected, unresolved)
        return
    count_rows = [row_id for row_id in sorted(group["rowIds"])
                  if any(count_cell(selected.supply_row_values.get(row_id, {}).get(field)) is not None
                         for field in _COUNT_FIELDS)]
    if len(count_rows) != 1:
        return
    row_id = count_rows[0]
    if any(row_id in other["rowIds"] for other in groups if other is not group) \
            or not _merged_count_rows(row_id, selected) <= group["rowIds"]:
        return
    cells = selected.supply_row_values[row_id]
    recruited, reserve = recruitment_count(cells), count_cell(cells.get("reserveCount"))
    if recruited is not None and "totalSupplyHouseholdCount" not in group["rowValues"]:
        group["rowValues"]["totalSupplyHouseholdCount"] = recruited
    if len(group["targets"]) != 1:
        return
    target = group["targets"][0]
    if target["supplyHouseholdCount"] is None and recruited is not None:
        target["supplyHouseholdCount"] = recruited
    if target["reserveCount"] is None and reserve is not None:
        target["reserveCount"] = reserve
        target["rowIds"].add(row_id)
        unresolved[:] = [finding for finding in unresolved
                         if finding["path"] != f"supplyRows[{index}].targets[0].reserveCount"]
    if target["supplyHouseholdCount"] is not None:
        target["rowIds"].add(row_id)


_COUNT_FIELDS = ("totalSupplyHouseholdCount", "supplyHouseholdCount", "reserveCount")


def _fill_block_counts(index: int, group: dict, groups: list[dict], unit: "_HouseTypeUnit",
                       selected: pages.PromptPages, unresolved: list[dict]) -> None:
    """주택타입 여럿을 묶은 주택형 공급행(84 = 84A·84B…)의 모집수는 주택타입 행마다 따로 적힌 모집수의 합이다(사용자 결정).
    예비자 수도 같은 행들의 합이다. 단지 주택타입은 세부 타입마다 따로 두고 이 합은 공고 공급행 한 줄에만 둔다.
    병합된 공통 수량 칸이 섞이거나, 한 타입이라도 수가 없거나, 다른 공급행도 그 행을 가리키면 정하지 않는다.
    합은 group["blockCounts"]에 두어 원문 대조가 그 값을 확인된 값으로 본다. 모델이 낸 값은 바꾸지 않는다.
    합을 정하지 못한 필드는 None으로 두어, 모델이 낸 한 타입의 수(84A 41)도 원문 대조에서 비운다.
    근거 행(rowIds)은 넓히지 않는다. 다른 주택타입 행의 면적·금액 칸으로 다른 값이 통과하지 않게 수량만 이 합과 대조한다."""
    group["blockCounts"] = dict.fromkeys(_COUNT_FIELDS)
    start = unit.rows[0]
    block = sorted((row_id for row_id in selected.supply_row_values
                    if (row := _house_type_row(row_id, selected)) is not None and row.start == start.start),
                   key=lambda row_id: int(row_id.rsplit(":R", 1)[1]))
    if len(block) != start.block_size or any(_merged_count_rows(row_id, selected) != {row_id} for row_id in block) \
            or any(set(block) & other["rowIds"] for other in groups if other is not group):
        return
    recruited = [recruitment_count(selected.supply_row_values[row_id]) for row_id in block]
    reserves = [count_cell(selected.supply_row_values[row_id].get("reserveCount")) for row_id in block]
    if any(count is None for count in recruited):
        return
    counts = {"totalSupplyHouseholdCount": sum(recruited), "supplyHouseholdCount": sum(recruited)}
    if all(count is not None for count in reserves):
        counts["reserveCount"] = sum(reserves)
    group["blockCounts"].update(counts)
    if "totalSupplyHouseholdCount" not in group["rowValues"]:
        group["rowValues"]["totalSupplyHouseholdCount"] = counts["totalSupplyHouseholdCount"]
    if len(group["targets"]) != 1:
        return
    target = group["targets"][0]
    if target["supplyHouseholdCount"] is None:
        target["supplyHouseholdCount"] = counts["supplyHouseholdCount"]
    if target["reserveCount"] is None and "reserveCount" in counts:
        target["reserveCount"] = counts["reserveCount"]
        unresolved[:] = [finding for finding in unresolved
                         if finding["path"] != f"supplyRows[{index}].targets[0].reserveCount"]


def _merged_count_rows(row_id: str, selected: pages.PromptPages) -> set[str]:
    """그 행의 수량 칸과 세로 병합(None)으로 이어진 행들이다. 수량 열 하나라도 위 행에서 내려오거나 아래 행으로 이어지면
    그 칸은 여러 행이 함께 쓰는 수량이다. 병합이 없으면 그 행 하나다."""
    rows = {row_id}
    for table in selected.supply_tables:
        ids = [row.id for row in table.rows]
        if row_id not in ids:
            continue
        index = ids.index(row_id)
        for column in (table.columns[field] for field in _COUNT_FIELDS if field in table.columns):
            def merged(at: int) -> bool:
                return column < len(table.cells[at]) and table.cells[at][column] is None
            start = index
            while start > 0 and merged(start):
                start -= 1
            end = index
            while end + 1 < len(ids) and merged(end + 1):
                end += 1
            rows.update(ids[start:end + 1])
    return rows


def _table_subtypes(row_ids: list[str], housing_type: str | None, selected: pages.PromptPages) -> list[tables.TableSubtype]:
    """공급행이 가리키는 주택형(신청형) 행에 표의 주택타입 열에서 읽은 세부 타입이 있으면 그 세부 타입이다.
    표 칸에서 읽은 값이라 모델이 낸 세부 타입 대신 쓴다. 다른 주택형이 적힌 행의 세부 타입은 이 공급행 것이 아니다."""
    wanted = normalize_source_text(housing_type or "")
    if not wanted:
        return []
    return [subtype for row_id in row_ids
            if normalize_source_text(selected.supply_row_values.get(row_id, {}).get("housingType", "")) == wanted
            for subtype in selected.supply_row_subtypes.get(row_id, ())]


def _subtype_values(subtypes: list[tables.TableSubtype]) -> list[dict]:
    """같은 신청형 행이 여럿(신청형을 행마다 다시 적은 표, 두 표)이어도 한 공급행 안 세부 타입은 전용면적마다 하나다."""
    return [{"name": subtype.name, "exclusiveArea": subtype.exclusive_area, "commonArea": subtype.common_area,
             "supplyArea": subtype.supply_area, "constructedHouseholdCount": None}
            for subtype in tables.merge_subtypes(subtypes)]


_LABELED_AREA = re.compile(r"\(\s*([A-Za-z0-9]+)\s*\)\s*(\d+(?:\.\d+)?)")


def _labeled_areas(row_ids: list[str], selected: pages.PromptPages) -> dict[str, float]:
    """한 칸에 세부 주택형 면적을 "(A)36.02 (B)36.19"처럼 적은 전용면적 칸의 표시별 면적."""
    areas: dict[str, float] = {}
    for row_id in row_ids:
        cell = selected.supply_row_values.get(row_id, {}).get("exclusiveArea", "")
        for match in _LABELED_AREA.finditer(str(cell)):
            areas[match.group(1).upper()] = float(match.group(2))
    return areas


def _subtype_label(name: str, housing_type: str | None) -> str:
    """세부 주택형 이름의 면적 칸 표시. 36A·A·(A) 모두 A다."""
    label = re.sub(r"[()\s]", "", name).upper()
    base = re.match(r"\s*(\d+)", housing_type or "")
    if base and label.startswith(base.group(1)) and len(label) > len(base.group(1)):
        label = label[len(base.group(1)):]
    return label


def _area_from_source_cells(group: dict, selected: pages.PromptPages) -> None:
    """모델이 전용면적을 빠뜨린 공급행은 그 행이 가리키는 원문 표 칸의 전용면적이 하나로 정해지면 그 값을 쓴다.
    공급행을 (단지, 전용면적)으로 기존 공급행·주택형에 잇기 때문에 면적이 비면 관리자가 직접 맞춰야 한다.
    세부 주택형 묶음 행과, 같은 행에서 값이 서로 달라 비운 면적(None)은 채우지 않는다."""
    if group["subtypes"] or "exclusiveArea" in group["rowValues"]:
        return
    areas = set()
    wanted = normalize_source_text(group["housingType"] or "")
    for row_id in group["rowIds"]:
        values = selected.supply_row_values.get(row_id, {})
        # 단지명만 이어받으려고 가리킨 위 행처럼 다른 주택형이 적힌 행의 면적은 이 공급행의 면적이 아니다.
        # 주택타입마다 뽑은 공급행(84A)은 그 행의 주택타입 칸이 표기와 같다.
        if wanted and values.get("housingType") and normalize_source_text(values["housingType"]) != wanted \
                and normalize_source_text(values.get("houseType", "")) != wanted:
            continue
        cell = values.get("exclusiveArea", "")
        try:
            areas.add(float(str(cell).replace(",", "").strip()))
        except ValueError:
            continue
    if not areas and group["housingType"]:
        # 모델이 임대조건표 행만 가리키면 그 행에는 면적 칸이 없다. 공급표에서 형명이 같은 행의 면적이 하나면 그 값이다.
        # 그 행 ID를 공급행 원문 행에 더해야 뒤의 원문 대조가 면적을 지우지 않는다.
        found = {}
        complex_name = normalize_source_text(group["complexName"] or "")
        for row_id, values in selected.supply_row_values.items():
            # 다단지 공고에서 다른 단지의 같은 형명 행 면적을 가져오지 않는다. 단지명 칸이 있는 행은 같은 단지만 본다.
            if complex_name and values.get("complexName") and normalize_source_text(values["complexName"]) != complex_name:
                continue
            if normalize_source_text(values.get("housingType", "")) == wanted:
                try:
                    found[row_id] = float(str(values.get("exclusiveArea", "")).replace(",", "").strip())
                except ValueError:
                    continue
        if len(set(found.values())) == 1:
            group["rowIds"].update(found)
            areas = set(found.values())
    if len(areas) == 1:
        group["rowValues"]["exclusiveArea"] = areas.pop()


def _all_applicants_when_no_target_column(index: int, row: dict, source_by_path: dict[str, set[str]],
                                          selected: pages.PromptPages, unresolved: list[dict]) -> None:
    """공급표·임대조건표에 공급대상 열이 없는 행은 신청자 구분이 없는 행이다. 대상이 하나면 모델이 지어낸 이름(일반) 대신
    LH 정제가 같은 행에 쓰는 '전체'로 두고 미확인으로 남기지 않는다. 대상 열이 있는데 확인하지 못한 이름은 그대로 비워 둔다.
    원문 대조가 끝난 뒤에 정한다. '전체'는 원문 칸에 없는 값이라 대조하면 다시 지워지기 때문이다."""
    if len(row["targets"]) != 1 or row["targets"][0]["target"]:
        return
    path = f"supplyRows[{index}].targets[0]"
    row_ids = source_by_path.get(f"supplyRows[{index}]", set()) | source_by_path.get(path, set())
    # 입력 표에 없는 행 ID만 가진 항목은 표 구조를 모르므로 대상 열이 없다고 볼 수 없다. 금액 열을 신청자 묶음(가군·나군)으로
    # 나눈 표도 대상이 있는 표다.
    if not row_ids or any(row_id not in selected.supply_row_values for row_id in row_ids) \
            or any("target" in selected.supply_row_values[row_id] or selected.supply_row_target_values.get(row_id)
                   for row_id in row_ids):
        return
    row["targets"][0]["target"] = "전체"
    unresolved[:] = [finding for finding in unresolved if finding["path"] != f"{path}.target"]


def _group_name(verified: str | None, candidate: object, field: str, row_ids: list[str],
                selected: pages.PromptPages) -> str | None:
    """공급행을 묶을 이름. 원문에서 확인한 값, 행 ID의 원문 칸 값이 하나뿐이면 그 값, 아니면 응답 표기 순이다.
    입력 표에 없는 행 ID만 가진 항목은 무엇과 같은 행인지 알 수 없어 묶지 않는다(None)."""
    if verified:
        return verified
    known = [row_id for row_id in row_ids if row_id in selected.supply_row_ids]
    if not known:
        return None
    source_values = source_texts(field, known, selected.supply_row_values)
    if len(source_values) == 1:
        return source_values[0]
    return _compact_string(candidate)


def _compact_source_text(candidate: object, field: str, row_ids: list[str], selected: pages.PromptPages,
                         path: str, unresolved: list[dict], missing_value: str | None,
                         required: bool = True) -> str | None:
    source_values = source_texts(field, row_ids, selected.supply_row_values, selected.supply_row_target_values)
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
        cells = {row_id: target_cells(values.get("target"), row_id, selected.supply_row_values,
                                      selected.supply_row_target_values) for row_id in row_ids}
        ids = [row_id for row_id, cell in cells.items() if cell.get(field)]
        # 이름을 이어받으려고 넣은 다른 줄(예: 소득 없음 줄)도 같은 열에 값이 있어, 값이 실제로 맞는 줄을 먼저 쓴다.
        matching = [row_id for row_id in row_ids if source_cell_matches(values[field], cells[row_id], field)]
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
    """PDF 줄바꿈 때문에 대상 이름 괄호 주변에 생긴 추출 공백을 정리하고, 소득 유무 표기를 하나로 맞춘다."""
    if field != "target":
        return value
    value = re.sub(r"\s+", " ", value).strip()
    value = re.sub(r"\s+\(", "(", value)
    value = re.sub(r"\(\s+", "(", value)
    return canonical_target(re.sub(r"\s+\)", ")", value))


_INCOME = re.compile(r"^(?P<base>.*?)[\s(]*소득\s*\(?\s*(?P<mark>無|有|없음|있음|무|유|×|✕|[xX]|○|◯|[oO])\s*\)?\s*\)?$")
_INCOME_NONE = {"無", "없음", "무", "×", "✕", "x", "X"}


def canonical_target(name: str) -> str:
    """소득 유무로 나눈 공급대상 이름을 `청년(소득 없음)`·`청년(소득 있음)`으로 맞춘다(사용자 결정: 소득 유무로 나뉘면 대상이 둘).
    공고마다 소득無·소득(×)·소득 X처럼 달라 단지에 쌓이는 대상 이름이 갈리기 때문이다. 다른 이름은 그대로 둔다."""
    # 가운뎃점은 공고마다 다른 문자(·․‧・･ㆍ)와 띄어쓰기로 적어(신혼부부․ 한부모가족) 붙여 쓴 ·로 맞춘다.
    name = re.sub(r"\s*[·․‧・･ㆍ]\s*", "·", name.strip())
    # 영구임대 대상 표기도 공고마다 「가」군·가군으로 달라 낫표를 뗀다.
    name = re.sub(r"[「」『』]", "", name)
    match = _INCOME.match(name)
    if not match:
        return name
    label = "소득 없음" if match["mark"] in _INCOME_NONE else "소득 있음"
    base = match["base"].strip()
    return f"{base}({label})" if base else label


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
