"""공급 단계와 본문 단계의 부분 결과를 관리자 계약 하나로 합친다. 두 단계의 단지를 이름으로 맞추고
단지 경로(complexes[N])를 합친 순번으로 고친다. 공급행 값의 원문 대조는 조립(supply_assembly)에서 이미 끝났다."""

import json
import re

from ai_worker import checks
from ai_worker.checks import deduplicate
from ai_worker.supply_assembly import add_complex_name, address_key, complex_key, normalize_address


def merge(supply: dict, notice: dict) -> tuple[dict, list[str]]:
    """두 단계의 부분 결과를 관리자 계약으로 합친다. 경고는 두 단계의 단지 값이 서로 다른 경우다."""
    supply_data, notice_data = supply["data"], notice["data"]
    supply_rows = checks.objects(supply_data.get("supplyRows"))
    supply_complexes = checks.objects(supply_data.get("complexes"))
    notice_complexes = checks.objects(notice_data.get("complexes"))
    complexes, supply_indexes, notice_indexes, complex_unresolved, complex_warnings = _merge_complexes(
        supply_rows, supply_complexes, notice_complexes
    )
    supply_unresolved = checks.objects(supply_data.get("unresolvedFields"))
    notice_unresolved = checks.objects(notice_data.get("unresolvedFields"))
    _remap_complex_paths(supply_unresolved, supply_complexes, supply_indexes)
    _remap_complex_paths(notice_unresolved, notice_complexes, notice_indexes)

    supply_evidence = checks.objects(supply.get("evidence"))
    notice_evidence = checks.objects(notice.get("evidence"))
    _remap_complex_paths(supply_evidence, supply_complexes, supply_indexes)
    _remap_complex_paths(notice_evidence, notice_complexes, notice_indexes)
    evidence = [*supply_evidence, *notice_evidence]

    supply_not_applicable = checks.objects(supply.get("notApplicable"))
    notice_not_applicable = checks.objects(notice.get("notApplicable"))
    _remap_complex_paths(supply_not_applicable, supply_complexes, supply_indexes)
    _remap_complex_paths(notice_not_applicable, notice_complexes, notice_indexes)
    not_applicable = deduplicate([*supply_not_applicable, *notice_not_applicable])

    shared_counts = checks.objects(supply.get("sharedCounts"))
    _remap_complex_paths(shared_counts, supply_complexes, supply_indexes)
    unresolved = [*supply_unresolved, *notice_unresolved, *complex_unresolved]
    for index, complex_ in enumerate(complexes):
        for field in ("address", "totalHouseholdCount", "parkingCount"):
            path = f"complexes[{index}].{field}"
            if complex_.get(field) is None and not _is_not_applicable(path, not_applicable) and not any(
                item.get("path") == path for item in unresolved
            ):
                unresolved.append({"path": path, "reason": "공급 표와 본문에서 확인하지 못함"})
    tree = {
        "data": {
            "announcement": notice_data["announcement"],
            "receptionPlaces": notice_data["receptionPlaces"],
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
    return tree, complex_warnings


def _merge_complexes(supply_rows: list[dict], supply: list[dict], notice: list[dict]):
    names = []
    for row in supply_rows:
        name = row.get("complexReference", {}).get("sourceComplexName") if isinstance(row.get("complexReference"), dict) else None
        add_complex_name(names, name)
    for item in [*supply, *notice]:
        add_complex_name(names, item.get("sourceComplexName"))

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
            (supply, supply_indexes, ("address", "totalHouseholdCount", "parkingCount")),
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
                    if record[field] is not None and not _same_value(field, record[field], value):
                        record[field] = None
                        conflicted_fields.add(field)
                        reason = "표·본문 단계의 값이 달라 미확인으로 남김"
                        unresolved.append({"path": f"complexes[{index}].{field}", "reason": reason})
                        warnings.append(f"complexes[{index}].{field}: {reason}")
                    elif record[field] is None:
                        record[field] = value
        merged.append(record)
    return merged, supply_indexes, notice_indexes, unresolved, warnings


def _same_value(field: str, left: object, right: object) -> bool:
    """주소는 표기만 다른 같은 주소(시·도 약칭, 띄어쓰기)를 같은 값으로 본다. 먼저 읽은 공급 표 원문 값을 남긴다."""
    if field == "address" and isinstance(left, str) and isinstance(right, str):
        return address_key(normalize_address(left) or left) == address_key(normalize_address(right) or right)
    return left == right


def _is_not_applicable(path: str, items: list[dict]) -> bool:
    wildcard = re.sub(r"\[\d+\]", "[*]", path)
    return any(item.get("path") in (path, wildcard) for item in items)


def _remap_complex_paths(items: list[dict], source_complexes: list[dict], index_map: dict[int, int]) -> None:
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
