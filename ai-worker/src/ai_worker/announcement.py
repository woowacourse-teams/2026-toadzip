"""공고문 PDF 추출기. 고른 쪽의 텍스트와 표를 OpenAI Luna에 보내고, 결과를 PDF 보정 화면이 읽는 형식(result.example.json)으로 만든다.

LH 공고는 이미 수집한 공급행 틀을 함께 준다. 모델·문구·쪽 입력을 바꾸면 대표 PDF로 정확도를 다시 확인한다.
"""

import json
import logging
import re
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, replace
from decimal import Decimal, InvalidOperation
from importlib.resources import files
from typing import Protocol

from ai_worker import checks, jev, openai, pages, supply_grouping
from ai_worker.openai import Reply
from ai_worker.extraction import ATTEMPT_BUDGET, PermanentError
from ai_worker.jobs import Job

log = logging.getLogger(__name__)

KIND = "ANNOUNCEMENT_PDF_EXTRACTION"
SUPPLY_TABLE_UNSTRUCTURED = "SUPPLY_TABLE_UNSTRUCTURED"
# 쪽 처리가 이만큼도 남기지 않으면 모델을 부르지 않는다. 쪽마다 확인해 남은 쪽을 읽지 않고 바로 실패로 끝낸다.
# 모델 호출과 응답 뒤 검산을 위한 최소 시간 여유다.
MIN_MODEL_TIME = 240
PROMPTS = files("ai_worker") / "prompts"


def prompt_text(name: str) -> str:
    return (PROMPTS / name).read_text(encoding="utf-8")


INSTRUCTIONS = prompt_text("announcement-pdf-extraction.txt")
SUPPLY_INSTRUCTIONS = prompt_text("announcement-pdf-supply-extraction.txt")
SCHEMA = json.loads(prompt_text("announcement-pdf-extraction-schema.json"))
TEXT_PREFACE = prompt_text("text-preface.txt")
SCHEMA_SUFFIX = prompt_text("schema-suffix.txt")
LH_FRAME = prompt_text("lh-frame.txt")


class Model(Protocol):
    def chat(self, content: str, deadline: float) -> Reply: ...


@dataclass(frozen=True)
class LhSupplyRow:
    """Spring이 input.lhSupplyRows에 담은 LH 공급 API 행. 값은 LH가 보낸 문자열 그대로다.
    row_identifier는 공급 보정 작업실의 LH 행 식별자(LH:{panId}:SUPPLY:{order})다. 식별자를 담기 전에 넣은 작업이면 없다."""

    type_name: str
    exclusive_area: str
    supply_area: str
    supplied_unit_count: str
    row_identifier: str | None

    @classmethod
    def of(cls, row: dict) -> "LhSupplyRow":
        return cls(row["typeName"], row["exclusiveArea"], row["supplyArea"], row["suppliedUnitCount"], row.get("rowIdentifier"))

    def count(self) -> int | None:
        try:
            return int(self.supplied_unit_count)
        except (TypeError, ValueError):
            return None


def lh_rows(job_input: dict | None) -> list[LhSupplyRow]:
    return [LhSupplyRow.of(row) for row in (job_input or {}).get("lhSupplyRows", [])]


def link_lh_rows(tree: dict, rows: list[LhSupplyRow]) -> None:
    """모델은 공급행이 나온 LH 행을 프롬프트 목록의 번호(1부터)로 쓴다. 결과에는 그 번호 대신 Spring이 준 LH 행 식별자를 담아,
    화면이 목록 위치가 아니라 식별자로 작업실의 LH 행을 맞추게 한다. 번호가 없거나 범위를 벗어나면(검산이 경고함) null이다."""
    for row in checks.objects(tree["data"].get("supplyRows")):
        if "lhSourceRow" not in row:
            continue
        number = row["lhSourceRow"]
        valid = isinstance(number, int) and not isinstance(number, bool) and 1 <= number <= len(rows)
        row["lhSourceRow"] = rows[number - 1].row_identifier if valid else None


def stage_schema(stage: str) -> str:
    """관리자 스키마에서 이번 단계가 맡은 필드만 남겨 모델이 동시에 채울 필드를 줄인다."""
    if stage != "notice":
        raise ValueError(f"unknown extraction stage: {stage}")
    schema = json.loads(json.dumps(SCHEMA))
    root = schema["properties"]
    data = root["data"]
    data_properties = data["properties"]
    data_names = ("announcement", "receptionPlace", "complexes", "schedules", "unresolvedFields")
    root_names = ("notApplicable", "evidence")
    complex_fields = ("sourceComplexName", "address", "totalHouseholdCount", "parkingCount")

    data["properties"] = {name: data_properties[name] for name in data_names}
    data["properties"]["complexes"] = project_complex_schema(data_properties["complexes"], complex_fields)
    data["required"] = [name for name in data["required"] if name in data_names]
    schema["properties"] = {name: value for name, value in root.items() if name in (*root_names, "data")}
    schema["required"] = [name for name in schema["required"] if name in schema["properties"]]
    return json.dumps(schema, ensure_ascii=False, indent=2)


def project_complex_schema(schema: dict, fields: tuple[str, ...]) -> dict:
    item = json.loads(json.dumps(schema))
    properties = item["items"]["properties"]
    item["items"]["properties"] = {name: properties[name] for name in fields}
    item["items"]["required"] = [name for name in item["items"]["required"] if name in fields]
    return item


def build_supply_prompt(rows: list[LhSupplyRow], selected: pages.PromptPages) -> str:
    lh_frame = ""
    if rows:
        frame = "\n".join(
            f"{number}. 주택형={row.type_name} | 전용={row.exclusive_area} | 공급={row.supply_area} | 모집수={row.supplied_unit_count}"
            for number, row in enumerate(rows, start=1)
        )
        lh_frame = LH_FRAME + "\n[LH 공급 API 행]\n" + frame + "\n\n"
    return TEXT_PREFACE + "[추출 단계: 공급 표]\n" + SUPPLY_INSTRUCTIONS + lh_frame + "\n\n" + selected.supply_text


def build_notice_prompt(selected: pages.PromptPages) -> str:
    candidates = "\n".join(f"- {name}" for name in selected.complex_candidates) or "(후보를 읽지 못함)"
    context = "[공급 표에서 읽은 단지명 후보]\n" + candidates + "\n\n"
    return (TEXT_PREFACE + "[추출 단계: 공고 본문]\n" + INSTRUCTIONS + "\n\n" + context + SCHEMA_SUFFIX
            + stage_schema("notice") + "\n\n" + selected.notice_text)


ROW_FIELDS = ("totalSupplyHouseholdCount", "exclusiveArea", "commonArea", "supplyArea", "constructedHouseholdCount")


def compact_supply_tree(compact: dict, selected: pages.PromptPages) -> dict:
    """짧은 공급행 응답을 표 셀 근거에 맞춰 관리자 계약으로 조립한다."""
    groups: list[dict] = []
    group_indexes: dict[tuple, int] = {}
    unresolved: list[dict] = []
    compact_paths: dict[int, tuple[int, int | None]] = {}

    for item_index, item in enumerate(checks.objects(compact.get("data", {}).get("rows"))):
        unresolved_start = len(unresolved)
        row_ids = list(dict.fromkeys(value for value in item.get("rowIds", []) if isinstance(value, str))) \
            if isinstance(item.get("rowIds"), list) else []
        complex_name = compact_source_text(item.get("complexName"), "complexName", row_ids, selected,
                                           "supplyRows[__item__].complexReference.sourceComplexName", unresolved,
                                           missing_value="")
        housing_type = compact_source_text(item.get("housingType"), "housingType", row_ids, selected,
                                            "supplyRows[__item__].sourceHousingTypeName", unresolved,
                                            missing_value=None)
        has_target = "target" in item
        target = compact_source_text(item.get("target"), "target", row_ids, selected,
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
            subtype_name = compact_string(subtype.get("name"))
            if not subtype_name:
                continue
            raw_text = "".join(selected.supply_row_text.get(row_id, "") for row_id in row_ids)
            subtype_supported = normalize_source_text(subtype_name) in normalize_source_text(raw_text)
            if not subtype_supported:
                unresolved.append({"path": f"supplyRows[{group_index}].subtypes",
                                   "reason": f"세부 주택형 {subtype_name}을(를) 원문 표 행에서 확인하지 못함"})
            clean_subtype = {
                "name": subtype_name,
                "exclusiveArea": compact_number(subtype.get("exclusiveArea"), "exclusiveArea") if subtype_supported else None,
                "commonArea": compact_number(subtype.get("commonArea"), "commonArea") if subtype_supported else None,
                "constructedHouseholdCount": compact_number(
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
                path = f"supplyRows[{group_index}].{field}"
                unresolved.append({"path": path, "reason": "같은 주택형 행에서 서로 다른 값이 나와 미확인으로 남김"})

        # 코드가 표에서 읽을 때 하나로 정하지 못한 값은 그 경로의 미확인으로 남긴다.
        findings = [finding for finding in checks.objects(item.get("unresolved"))
                    if isinstance(finding.get("field"), str) and isinstance(finding.get("reason"), str)]
        unresolved.extend({"path": f"supplyRows[{group_index}].{finding['field']}", "reason": finding["reason"]}
                          for finding in findings if finding["field"] in ROW_FIELDS)
        if has_target:
            target_values = {
                "target": target,
                "supplyHouseholdCount": compact_number(item.get("supplyHouseholdCount"), "supplyHouseholdCount"),
                "reserveCount": compact_number(item.get("reserveCount"), "reserveCount"),
                "rentalDeposit": compact_number(item.get("rentalDeposit"), "rentalDeposit"),
                "monthlyRent": compact_number(item.get("monthlyRent"), "monthlyRent"),
                "convertedDeposit": compact_number(item.get("convertedDeposit"), "convertedDeposit"),
                "applicationCondition": compact_string(item.get("applicationCondition")),
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
            "lhSourceRow": group["rowValues"].get("lhSourceRow"),
            "expectedMoveInMonth": group["rowValues"].get("expectedMoveInMonth"),
            "totalSupplyHouseholdCount": compact_number(group["rowValues"].get("totalSupplyHouseholdCount"),
                                                         "totalSupplyHouseholdCount"),
            "exclusiveArea": compact_number(group["rowValues"].get("exclusiveArea"), "exclusiveArea"),
            "commonArea": compact_number(group["rowValues"].get("commonArea"), "commonArea"),
            "supplyArea": compact_number(group["rowValues"].get("supplyArea"), "supplyArea"),
            "constructedHouseholdCount": compact_number(group["rowValues"].get("constructedHouseholdCount"),
                                                          "constructedHouseholdCount"),
            "subtypes": group["subtypes"],
            "targets": [],
        }
        supply_rows.append(row)
        source_rows.append({"path": f"supplyRows[{index}]", "rowIds": row_ids})
        evidence.extend(compact_evidence(f"supplyRows[{index}]", row_ids, selected))
        evidence.extend(value_evidence(f"supplyRows[{index}]", row, ROW_FIELDS, row_ids, selected))
        for target_index, target_values in enumerate(group["targets"]):
            target_row_ids = sorted(target_values.pop("rowIds"))
            target = {name: target_values[name] for name in (
                "target", "supplyHouseholdCount", "reserveCount", "rentalDeposit", "monthlyRent",
                "convertedDeposit", "applicationCondition",
            )}
            row["targets"].append(target)
            path = f"supplyRows[{index}].targets[{target_index}]"
            source_rows.append({"path": path, "rowIds": target_row_ids})
            evidence.extend(compact_evidence(path, target_row_ids, selected))
            evidence.extend(value_evidence(path, target, TARGET_VALUE_FIELDS, target_row_ids, selected))

    complexes, complex_row_ids, complex_unresolved = compact_complexes(supply_rows, groups, selected)
    unresolved.extend(complex_unresolved)
    for index, row_ids in enumerate(complex_row_ids):
        evidence.extend(compact_evidence(f"complexes[{index}]", sorted(row_ids), selected))

    source_by_path = {item["path"]: set(item["rowIds"]) for item in source_rows}
    shared_counts = []
    for shared in checks.objects(compact.get("sharedCounts")):
        field = shared.get("field")
        count = compact_number(shared.get("count"), field) if isinstance(field, str) else None
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
    check_source_rows(supply_rows, row_source_map, selected.supply_row_text, evidence,
                      tree["data"]["unresolvedFields"], selected.supply_row_values)
    tree["data"]["unresolvedFields"] = deduplicate(tree["data"]["unresolvedFields"])
    return tree


def compact_source_text(candidate: object, field: str, row_ids: list[str], selected: pages.PromptPages,
                        path: str, unresolved: list[dict], missing_value: str | None,
                        required: bool = True) -> str | None:
    source_values = source_texts(field, row_ids, selected.supply_row_values)
    if isinstance(candidate, str) and candidate.strip():
        matched = next((value for value in source_values
                        if normalize_source_text(value) == normalize_source_text(candidate)), None)
        if matched is not None:
            return normalize_source_display(matched, field)
        unresolved.append({"path": path, "reason": "추출한 값이 같은 의미의 원문 표 열에서 확인되지 않음"})
        return missing_value
    if len(source_values) == 1:
        return normalize_source_display(source_values[0], field)
    if required:
        reason = "원문 표 행 ID가 없거나 해당 열의 값을 확인하지 못함"
        unresolved.append({"path": path, "reason": reason})
    elif len(source_values) > 1:
        unresolved.append({"path": path, "reason": "여러 원문 표 값이 있어 하나로 결정하지 못함"})
    return missing_value


def source_texts(field: str, row_ids, row_values: dict[str, dict[str, str]]) -> list[str]:
    """행 ID들의 같은 의미 원문 칸. 공급대상 머리글 아래 세부 구분 칸(소득 있음·없음)이 따로 있으면
    공급대상은 둘을 합친 이름(청년(소득 없음))이다."""
    values = [row_values.get(row_id, {}).get(field) for row_id in row_ids]
    texts = [value for value in values if isinstance(value, str) and value.strip()]
    if field == "target":
        details = [row_values.get(row_id, {}).get("targetDetail") for row_id in row_ids]
        texts += [f"{text}({detail})" for text in list(texts) for detail in details if detail]
    return list(dict.fromkeys(texts))


def compact_number(value: object, field: str) -> int | float | None:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return None
    if field in ("exclusiveArea", "commonArea", "supplyArea"):
        return value
    if isinstance(value, int) or value.is_integer():
        return int(value)
    return None


def compact_string(value: object) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None


def compact_complexes(supply_rows: list[dict], groups: list[dict], selected: pages.PromptPages):
    names = []
    for row in supply_rows:
        name = row["complexReference"]["sourceComplexName"]
        add_name(names, name)
    complexes, complex_row_ids, unresolved = [], [], []
    for name in names:
        row_ids = set()
        values = {"totalHouseholdCount": set(), "parkingCount": set()}
        for index, group in enumerate(groups):
            if complex_key(group["complexName"]) != complex_key(name):
                continue
            row_ids.update(group["rowIds"])
            for row_id in group["rowIds"]:
                cells = selected.supply_row_values.get(row_id, {})
                for field in values:
                    parsed = integer_cell(cells.get(field))
                    if parsed is not None:
                        values[field].add(parsed)
        record = {"sourceComplexName": name, "address": None}
        for field, candidates in values.items():
            record[field] = next(iter(candidates)) if len(candidates) == 1 else None
            if len(candidates) > 1:
                unresolved.append({"path": f"complexes[{len(complexes)}].{field}",
                                   "reason": "공급 표의 같은 단지 값이 서로 달라 미확인으로 남김"})
        complexes.append(record)
        complex_row_ids.append(row_ids)
    return complexes, complex_row_ids, unresolved


def integer_cell(value: object) -> int | None:
    if not isinstance(value, str):
        return None
    number = re.sub(r"[^\d-]", "", value.replace(",", ""))
    try:
        return int(number) if number and number != "-" else None
    except ValueError:
        return None


TARGET_VALUE_FIELDS = ("supplyHouseholdCount", "reserveCount", "rentalDeposit", "monthlyRent", "convertedDeposit")


def value_evidence(path: str, values: dict, fields: tuple[str, ...], row_ids: list[str],
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
        result.extend(compact_evidence(f"{path}.{field}", matching or ids, selected)[:1])
    return result


def compact_evidence(path: str, row_ids: list[str], selected: pages.PromptPages) -> list[dict]:
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


class ColumnJudge(Protocol):
    def count_meanings(self, state: dict, headers: dict[str, str], reserve_only: bool,
                       timeout: float = jev.TIMEOUT) -> dict[str, jev.Meaning]: ...


class AnnouncementPdfExtractor:
    def __init__(self, model: Model, column_judge: ColumnJudge | None = None):
        self.model = model
        # 없으면 코드가 읽은 수량 열의 뜻을 확인할 수 없어 공급표는 늘 모델 단계로 처리한다.
        self.column_judge = column_judge

    def counts_confirmed(self, grouped: supply_grouping.Grouped, first_pages: list[str], cutoff: float) -> bool:
        """정규식이 고른 수량 열의 뜻을 Jev가 같은 뜻으로, 기준 확률 이상으로 판단할 때만 코드 경로를 쓴다.
        Jev 호출 실패는 '다르다'가 아니라 판단 없음이라 모델 단계로 넘긴다.
        cutoff(time.monotonic 기준)가 지나면 묻지 않는다. 공급 단계 모델 호출에 쓸 시간을 남긴다."""
        if self.column_judge is None:
            log.info("Jev 키가 없어 수량 열의 뜻을 확인하지 못하므로 공급표를 모델 단계로 처리합니다.")
            return False
        if not grouped.count_columns:
            log.info("머리글 있는 수량표가 없어 수량 열의 뜻을 확인하지 못하므로 공급표를 모델 단계로 처리합니다.")
            return False
        reserve_only = jev.is_reserve_notice(first_pages)
        for table, header_count, fields in grouped.count_columns:
            if reserve_only and any(jev.FIELD_MEANING[name] in jev.WINNER_OPTIONS for name in fields):
                log.info("예비자만 모집하는 공고의 원본 %d쪽 수량표에 당첨자 열이 있어 공급표를 모델 단계로 처리합니다.",
                         table.page)
                return False
            remaining = cutoff - time.monotonic()
            if remaining <= 0:
                log.info("Jev에 물을 시간이 남지 않아 공급표를 모델 단계로 처리합니다.")
                return False
            state, paths = jev.table_state(table, header_count)
            try:
                meanings = self.column_judge.count_meanings(
                    state, {f"column_{column}": paths[column] for column in fields.values()}, reserve_only,
                    timeout=remaining)
            except jev.JevError as error:
                log.warning("%s 공급표를 모델 단계로 처리합니다.", error)
                return False
            for field_name, column in fields.items():
                meaning = meanings[f"column_{column}"]
                if meaning.choice != jev.FIELD_MEANING[field_name] or meaning.probability < jev.THRESHOLD:
                    log.info("원본 %d쪽 '%s' 열을 Jev가 %s(%.2f)로 판단해 공급표를 모델 단계로 처리합니다.",
                             table.page, paths[column], meaning.choice, meaning.probability)
                    return False
        return True

    def extract(self, job: Job) -> dict:
        if job.kind != KIND:
            raise PermanentError("UNSUPPORTED_JOB", "이 워커가 처리하지 않는 작업입니다.")
        # 시도 예산은 PDF 처리부터 잰다. 모델 호출만 재면 큰 PDF의 쪽 처리 시간만큼 마감을 넘는다.
        deadline = time.monotonic() + ATTEMPT_BUDGET.total_seconds()
        rows = lh_rows(job.input)
        model_cutoff = deadline - MIN_MODEL_TIME
        document = pages.open_pdf(job.pdf)
        with document:
            texts = pages.page_texts(document, model_cutoff)
            selection = pages.select(texts)
            selected = pages.prompt_pages(document, selection.pages, model_cutoff)
        if selected.unstructured_supply_pages:
            numbers = ", ".join(str(number) for number in selected.unstructured_supply_pages)
            raise PermanentError(
                SUPPLY_TABLE_UNSTRUCTURED,
                f"공급·임대 표 구조를 읽지 못해 추출을 중단했습니다. 원본 PDF {numbers}쪽을 확인해 주세요.",
            )
        notice_prompt = build_notice_prompt(selected)
        # 공급표 구조를 코드가 확신하면 숫자를 셀에서 직접 읽고 공급 단계 모델 호출을 생략한다.
        # LH 공고는 모델이 LH API 행 번호(lhSourceRow)로 공급행을 나누고 맞추므로 모델 단계를 그대로 쓴다.
        grouped = None if rows else supply_grouping.group(
            selected.supply_tables, selected.supply_row_values, selected.tableless_supply_pages)
        # 두 역할은 서로 의존하지 않는다. 같은 남은 마감 안에서 병렬 호출해 분리 비용이 순차 지연으로 누적되지 않게 한다.
        model_deadline = deadline - openai.READ_TIMEOUT
        with ThreadPoolExecutor(max_workers=2, thread_name_prefix="announcement-extraction") as pool:
            # 본문 단계는 공급표와 상관없이 늘 부르므로 먼저 보내고, 그동안 Jev로 수량 열의 뜻을 확인한다.
            notice_future = pool.submit(self.model.chat, notice_prompt, model_deadline)
            if grouped and not self.counts_confirmed(grouped, texts[:2], model_cutoff):
                grouped = None
            supply_future = None if grouped else pool.submit(
                self.model.chat, build_supply_prompt(rows, selected), model_deadline)
            supply_reply = supply_future.result() if supply_future else None
            notice_reply = notice_future.result()
        if grouped:
            selected = replace(selected, supply_row_values=grouped.row_values)
            compact_supply = grouped.compact
        else:
            compact_supply = checks.first_json_object(supply_reply.content, {"rows": list})
        supply_tree = compact_supply_tree(compact_supply, selected)
        notice_tree = checks.first_json_object(notice_reply.content, {
            "announcement": dict, "receptionPlace": dict, "complexes": list, "schedules": list,
            "unresolvedFields": list,
        })
        warnings = list(grouped.warnings) if grouped else []
        if selection.whole_document:
            warnings.append(f"공급표·임대조건표 쪽을 찾지 못해 전체 {len(texts)}쪽을 보냈습니다.")
        # 코드가 공급표를 읽었으면 공급 단계 호출이 없으므로 아래 두 안내는 사실이 아니다.
        if selected.table_structure_fallback and not grouped:
            warnings.append("표 셀 구조를 분리하지 못해 일부 쪽 텍스트를 공급 단계에도 전달했습니다.")
        if selected.ambiguous_supply_tables and not grouped:
            warnings.append("공급 표인지 판단이 불확실한 표를 공급·본문 단계 양쪽에 전달했습니다.")
        # 공급 근거는 원문 행 ID에서 원본 쪽을 복원하므로 첨부 쪽 번호 변환을 하지 않는다.
        warnings += remap_stage_pages(notice_tree, selected.notice_pages)
        # merge_stages는 페이지 변환 전에 tree를 만들므로, 최종 결과의 근거를 원본 PDF 쪽 번호로 변환한다.
        tree, merge_warnings = merge_stages(
            supply_tree, notice_tree, selected.supply_row_text, selected.supply_row_values
        )
        warnings += merge_warnings
        warnings += checks.review(tree, list(range(1, len(texts) + 1)), texts, [row.type_name for row in rows],
                                  [row.count() for row in rows], selected.rental_rows)
        link_lh_rows(tree, rows)
        checks.normalize_note_paths(tree, warnings)
        usage = combined_usage(*([supply_reply.usage] if supply_reply else []), notice_reply.usage)
        log.info("작업 %d 추출 완료 pages=%d/%d tableRows=%d lhRows=%d warnings=%d supply=%s usage=%s", job.id,
                 len(selection.pages), len(texts), len(selected.supply_row_ids), len(rows), len(warnings),
                 "code" if grouped else "model", usage)
        return {
            "json": json.dumps(tree, ensure_ascii=False),
            "pages": selection.pages,
            "totalPages": len(texts),
            "warnings": warnings,
            "usage": usage,
        }


def remap_stage_pages(tree: dict, source_pages: list[int]) -> list[str]:
    warnings: list[str] = []
    checks.remap_pages(checks.objects(tree.get("sharedCounts")), source_pages, warnings)
    checks.remap_pages(checks.objects(tree.get("evidence")), source_pages, warnings)
    return warnings


def merge_stages(supply: dict, notice: dict, source_row_text: dict[str, str],
                 source_row_values: dict[str, dict[str, str]] | None = None) -> tuple[dict, list[str]]:
    """두 단계의 부분 결과를 관리자 계약으로 합치고, 원문 표 행 근거가 빠진 경로는 미확인으로 남긴다."""
    supply_data, notice_data = supply["data"], notice["data"]
    supply_rows = checks.objects(supply_data.get("supplyRows"))
    supply_complexes = checks.objects(supply_data.get("complexes"))
    notice_complexes = checks.objects(notice_data.get("complexes"))
    complexes, supply_indexes, notice_indexes, complex_unresolved, complex_warnings = merge_complexes(
        supply_rows, supply_complexes, notice_complexes
    )
    supply_unresolved = checks.objects(supply_data.get("unresolvedFields"))
    notice_unresolved = checks.objects(notice_data.get("unresolvedFields"))
    remap_complex_paths(supply_unresolved, supply_complexes, supply_indexes)
    remap_complex_paths(notice_unresolved, notice_complexes, notice_indexes)

    supply_evidence = checks.objects(supply.get("evidence"))
    notice_evidence = checks.objects(notice.get("evidence"))
    remap_complex_paths(supply_evidence, supply_complexes, supply_indexes)
    remap_complex_paths(notice_evidence, notice_complexes, notice_indexes)
    evidence = [*supply_evidence, *notice_evidence]

    supply_not_applicable = checks.objects(supply.get("notApplicable"))
    notice_not_applicable = checks.objects(notice.get("notApplicable"))
    remap_complex_paths(supply_not_applicable, supply_complexes, supply_indexes)
    remap_complex_paths(notice_not_applicable, notice_complexes, notice_indexes)
    not_applicable = deduplicate([*supply_not_applicable, *notice_not_applicable])

    shared_counts = checks.objects(supply.get("sharedCounts"))
    remap_complex_paths(shared_counts, supply_complexes, supply_indexes)
    source_rows = source_row_map(supply.get("sourceRows"))
    row_unresolved = []
    source_warnings = check_source_rows(
        supply_rows, source_rows, source_row_text, checks.objects(supply.get("evidence")), row_unresolved,
        source_row_values,
    )
    unresolved = [*supply_unresolved, *notice_unresolved, *row_unresolved, *complex_unresolved]
    for index, complex_ in enumerate(complexes):
        for field in ("address", "totalHouseholdCount", "parkingCount"):
            path = f"complexes[{index}].{field}"
            if complex_.get(field) is None and not is_not_applicable(path, not_applicable) and not any(
                item.get("path") == path for item in unresolved
            ):
                unresolved.append({"path": path, "reason": "공급 표와 본문에서 확인하지 못함"})
    tree = {
        "data": {
            "announcement": notice_data["announcement"],
            "receptionPlace": notice_data["receptionPlace"],
            "complexes": complexes,
            "supplyRows": supply_rows,
            "schedules": notice_data["schedules"],
            "unresolvedFields": deduplicate(unresolved),
        },
        "sharedCounts": deduplicate(shared_counts),
        "notApplicable": deduplicate(not_applicable),
        "evidence": evidence,
    }
    checks.first_json_object(json.dumps(tree, ensure_ascii=False))
    return tree, [*complex_warnings, *source_warnings]


def merge_complexes(supply_rows: list[dict], supply: list[dict], notice: list[dict]):
    names = []
    for row in supply_rows:
        name = row.get("complexReference", {}).get("sourceComplexName") if isinstance(row.get("complexReference"), dict) else None
        add_name(names, name)
    for item in [*supply, *notice]:
        add_name(names, item.get("sourceComplexName"))

    canonical_names = {complex_key(name): name for name in names}
    for row in supply_rows:
        reference = row.get("complexReference")
        name = reference.get("sourceComplexName") if isinstance(reference, dict) else None
        if isinstance(name, str) and complex_key(name) in canonical_names:
            reference["sourceComplexName"] = canonical_names[complex_key(name)]

    merged, supply_indexes, notice_indexes, unresolved, warnings = [], {}, {}, [], []
    for name in names:
        index = len(merged)
        record = {"sourceComplexName": name, "address": None, "totalHouseholdCount": None, "parkingCount": None}
        conflicted_fields = set()
        for source_items, source_map, fields in (
            (supply, supply_indexes, ("totalHouseholdCount", "parkingCount")),
            (notice, notice_indexes, ("address", "totalHouseholdCount", "parkingCount")),
        ):
            for source_index, item in enumerate(source_items):
                source_name = item.get("sourceComplexName")
                if not isinstance(source_name, str) or complex_key(source_name) != complex_key(name):
                    continue
                source_map[source_index] = index
                for field in fields:
                    value = item.get(field)
                    if value is None or field in conflicted_fields:
                        continue
                    if record[field] is not None and record[field] != value:
                        record[field] = None
                        conflicted_fields.add(field)
                        reason = "표·본문 단계의 값이 달라 미확인으로 남김"
                        unresolved.append({"path": f"complexes[{index}].{field}", "reason": reason})
                        warnings.append(f"complexes[{index}].{field}: {reason}")
                    elif record[field] is None:
                        record[field] = value
        merged.append(record)
    return merged, supply_indexes, notice_indexes, unresolved, warnings


def add_name(names: list[str], name: object) -> None:
    if isinstance(name, str) and name and all(complex_key(existing) != complex_key(name) for existing in names):
        names.append(name)


def complex_key(name: str) -> str:
    return re.sub(r"\s+", "", name)


def is_not_applicable(path: str, items: list[dict]) -> bool:
    wildcard = re.sub(r"\[\d+\]", "[*]", path)
    return any(item.get("path") in (path, wildcard) for item in items)


def remap_complex_paths(items: list[dict], source_complexes: list[dict], index_map: dict[int, int]) -> None:
    for item in items:
        path = item.get("path")
        if not isinstance(path, str) or not path.startswith("complexes["):
            continue
        closing = path.find("]")
        if closing < 0:
            continue
        try:
            source_index = int(path[len("complexes["):closing])
        except ValueError:
            continue
        target_index = index_map.get(source_index)
        if target_index is not None:
            item["path"] = f"complexes[{target_index}]" + path[closing + 1:]


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
                      source_row_values: dict[str, dict[str, str]] | None = None) -> list[str]:
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
            check_string_source_values(path, supply_rows, row_ids, values, unresolved, warnings)
            check_numeric_source_values(path, supply_rows, row_ids, values, unresolved, warnings)
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
        clear_unverified_string_values(path, supply_rows, unresolved, warnings, reason)
        clear_unverified_numeric_values(path, supply_rows, unresolved, warnings, reason)
    return warnings


def check_numeric_source_values(path: str, rows: list[dict], row_ids: set[str],
                                source_row_values: dict[str, dict[str, str]],
                                unresolved: list[dict], warnings: list[str]) -> None:
    """표 근거가 같은 의미의 머리글 열을 지지하지 않으면 수치 값을 미확인으로 낮춘다."""
    match = re.fullmatch(r"supplyRows\[(\d+)\](?:\.targets\[(\d+)\])?", path)
    if not match:
        return
    values, numeric_fields = supply_values_at_path(path, rows)
    if values is None:
        return
    for field in numeric_fields:
        value = values.get(field)
        if value is None:
            continue
        cells = [source_row_values.get(row_id, {}) for row_id in row_ids]
        if any(numeric_cell_matches(value, cell.get(field, ""), field, cell.get(f"{field}Unit"))
               for cell in cells):
            continue
        values[field] = None
        field_path = f"{path}.{field}"
        reason = "추출한 수치가 같은 의미의 원문 표 열에서 확인되지 않음"
        unresolved.append({"path": field_path, "reason": reason})
        warnings.append(f"{field_path}: {reason}")


def check_string_source_values(path: str, rows: list[dict], row_ids: set[str],
                               source_row_values: dict[str, dict[str, str]],
                               unresolved: list[dict], warnings: list[str]) -> None:
    """단지명·주택형·공급대상은 해당 의미의 원문 셀과 비교한다."""
    for values, field, source_field in supply_string_fields_at_path(path, rows):
        value = values.get(field)
        if not isinstance(value, str) or not value:
            continue
        if any(normalize_source_text(cell) == normalize_source_text(value)
               for cell in source_texts(source_field, row_ids, source_row_values)):
            continue
        values[field] = None if field == "sourceHousingTypeName" else ""
        field_path = f"{path}.complexReference.sourceComplexName" if field == "sourceComplexName" else f"{path}.{field}"
        reason = "추출한 값이 같은 의미의 원문 표 열에서 확인되지 않음"
        unresolved.append({"path": field_path, "reason": reason})
        warnings.append(f"{field_path}: {reason}")


def clear_unverified_string_values(path: str, rows: list[dict], unresolved: list[dict], warnings: list[str],
                                   reason: str) -> None:
    for values, field, _ in supply_string_fields_at_path(path, rows):
        value = values.get(field)
        if not isinstance(value, str) or not value:
            continue
        values[field] = None if field == "sourceHousingTypeName" else ""
        field_path = f"{path}.complexReference.sourceComplexName" if field == "sourceComplexName" else f"{path}.{field}"
        field_reason = f"{reason}; 문자열 원문을 확인하지 못함"
        unresolved.append({"path": field_path, "reason": field_reason})
        warnings.append(f"{field_path}: {field_reason}")


def supply_string_fields_at_path(path: str, rows: list[dict]) -> list[tuple[dict, str, str]]:
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


def normalize_source_display(value: str, field: str) -> str:
    """PDF 줄바꿈 때문에 대상 이름 괄호 주변에 생긴 추출 공백만 정리한다."""
    if field != "target":
        return value
    value = re.sub(r"\s+", " ", value).strip()
    value = re.sub(r"\s+\(", "(", value)
    value = re.sub(r"\(\s+", "(", value)
    return re.sub(r"\s+\)", ")", value)


def clear_unverified_numeric_values(path: str, rows: list[dict], unresolved: list[dict], warnings: list[str],
                                    reason: str) -> None:
    values, fields = supply_values_at_path(path, rows)
    if values is None:
        return
    for field in fields:
        if values.get(field) is None:
            continue
        values[field] = None
        field_path = f"{path}.{field}"
        field_reason = f"{reason}; 수치 원문을 확인하지 못함"
        unresolved.append({"path": field_path, "reason": field_reason})
        warnings.append(f"{field_path}: {field_reason}")


def supply_values_at_path(path: str, rows: list[dict]) -> tuple[dict | None, tuple[str, ...]]:
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


def deduplicate(items: list[dict]) -> list[dict]:
    seen, result = set(), []
    for item in items:
        marker = json.dumps(item, ensure_ascii=False, sort_keys=True)
        if marker not in seen:
            seen.add(marker)
            result.append(item)
    return result


def combined_usage(*usages: dict) -> dict:
    result = {}
    for key in ("promptTokens", "outputTokens", "thinkingTokens"):
        values = [usage.get(key) for usage in usages]
        result[key] = sum(values) if all(isinstance(value, int) and not isinstance(value, bool) for value in values) else None
    return result
