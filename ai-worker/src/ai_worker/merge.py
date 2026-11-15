"""공급·본문·신청자격 단계의 부분 결과를 관리자 계약 하나로 합친다. 두 단계의 단지를 이름으로 맞추고
단지 경로(complexes[N])를 합친 순번으로 고친다. 공급표 값의 원문 대조는 조립(supply_assembly)에서 이미 끝났고,
여기서는 공급표 모델의 단지명을 합친 단지 이름으로 맞추고 신청자격 조건을 공급대상에 붙인다."""

import json
import re

from ai_worker import checks
from ai_worker.supply_sheet import Cite, SupplySheet
from ai_worker.checks import deduplicate
from ai_worker.eligibility import Extracted
from ai_worker.supply_assembly import add_complex_name, address_key, complex_key, normalize_address


def merge(supply: dict, sheet: SupplySheet, notice: dict,
          eligibility: Extracted | None = None) -> tuple[dict, list[str]]:
    """단계별 부분 결과를 관리자 계약으로 합친다. supply는 공급 단계의 단지 부분, sheet는 공급표 모델이다.
    경고는 두 단계의 단지 값이 서로 다른 경우다. 신청자격은 data.eligibility로 두고, 묶음 요약은 공급대상 신청 조건
    (applicationCondition)에 합친다. 공급표는 결과 tree에 넣지 않는다(검산 뒤 supply_sheet가 내보낸다)."""
    supply_data, notice_data = supply["data"], notice["data"]
    supply_complexes = checks.objects(supply_data.get("complexes"))
    notice_complexes = checks.objects(notice_data.get("complexes"))
    complexes, supply_indexes, notice_indexes, complex_unresolved, complex_warnings = _merge_complexes(
        sheet, supply_complexes, notice_complexes
    )
    supply_unresolved = checks.objects(supply_data.get("unresolvedFields"))
    notice_unresolved = _outside_sheet(notice_data.get("unresolvedFields"))
    _remap_complex_paths(supply_unresolved, supply_complexes, supply_indexes)
    _remap_complex_paths(notice_unresolved, notice_complexes, notice_indexes)

    supply_evidence = checks.objects(supply.get("evidence"))
    notice_evidence = _outside_sheet(notice.get("evidence"))
    _remap_complex_paths(supply_evidence, supply_complexes, supply_indexes)
    _remap_complex_paths(notice_evidence, notice_complexes, notice_indexes)
    evidence = [*supply_evidence, *notice_evidence]

    supply_not_applicable = checks.objects(supply.get("notApplicable"))
    notice_not_applicable = _outside_sheet(notice.get("notApplicable"))
    _remap_complex_paths(supply_not_applicable, supply_complexes, supply_indexes)
    _remap_complex_paths(notice_not_applicable, notice_complexes, notice_indexes)
    not_applicable = deduplicate([*supply_not_applicable, *notice_not_applicable])

    unresolved = [*supply_unresolved, *notice_unresolved, *complex_unresolved]
    for index, complex_ in enumerate(complexes):
        for field in ("address", "totalHouseholdCount", "parkingCount"):
            path = f"complexes[{index}].{field}"
            if complex_.get(field) is None and not _is_not_applicable(path, not_applicable) and not any(
                item.get("path") == path for item in unresolved
            ):
                unresolved.append({"path": path, "reason": "공급 표와 본문에서 확인하지 못함"})
    if eligibility is not None:
        evidence += eligibility.evidence
        _apply_conditions(sheet, eligibility.conditions, eligibility.condition_evidence)
        _link_supply_targets(eligibility.data, sheet)
    tree = {
        "data": {
            "announcement": notice_data["announcement"],
            "receptionPlaces": notice_data["receptionPlaces"],
            "complexes": complexes,
            "schedules": notice_data["schedules"],
            "eligibility": eligibility.data if eligibility is not None else None,
            "unresolvedFields": deduplicate(unresolved),
        },
        "notApplicable": deduplicate(not_applicable),
        "evidence": evidence,
    }
    checks.first_json_object(json.dumps(tree, ensure_ascii=False), {"announcement": dict, "schedules": list})
    return tree, complex_warnings


# 공급정보의 미확인·근거는 공급표 안에 있다. 본문 단계가 옛 공급행 경로(supplyRows·sharedCounts)를 쓰면 가리킬 곳이 없어 뺀다.
_OLD_SUPPLY_PATH = re.compile(r"(?:supplyRows|sharedCounts)(?:$|[.\[])")


def _outside_sheet(items: object) -> list[dict]:
    return [item for item in checks.objects(items)
            if not (isinstance(item.get("path"), str) and _OLD_SUPPLY_PATH.match(item["path"]))]


_CONDITION_LIMIT = 255
_CONDITION_SEPARATOR = " · "


def _apply_conditions(sheet: SupplySheet, conditions, notice_evidence: list[dict]) -> None:
    """본문 신청자격을 대상 신청 조건에 합친다. 공고 전체 조건(target 없음)은 모든 대상에, 대상 조건은 이름이 같거나 한쪽이 다른 쪽을
    품는 대상(고령자 ↔ 고령자(주거약자))에 붙이고, 표에 적힌 조건은 뒤에 둔다. 대상 이름은 바꾸지 않는다(PDF 보정이 기존 대상과 짝짓는 이름).
    255자(저장 칸 길이)를 넘으면 조건 단위로 자른다. 근거는 처음 붙인 본문 조건의 근거를 그 대상 칸에 옮긴다."""
    items = [item for item in checks.objects(conditions) if _present(item.get("condition"))]
    if not items:
        return
    quotes = {item.get("path"): item for item in notice_evidence}
    for _, entry in sheet.entries():
        matched = [item for item in items if _condition_applies(item.get("target"), entry.label)]
        if not matched:
            continue
        own = entry.values.get("applicationCondition")
        notice_parts = [item["condition"].strip() for item in matched]
        entry.values["applicationCondition"] = _joined_within_limit(list(dict.fromkeys(notice_parts)),
                                                                    own.strip() if _present(own) else None)
        source = quotes.get(f"applicationConditions[{conditions.index(matched[0])}]")
        if source:
            sheet.evidence.append(Cite(entry, "applicationCondition", source.get("page"), source.get("quote")))


def _link_supply_targets(data: dict | None, sheet: SupplySheet) -> None:
    """신청자격 대상마다 그 대상이 속한 공급 표 대상(supplyTarget)과 같은 규칙(_condition_applies)으로 맞은 공급표 대상 이름을
    원문 그대로 싣는다(supplyTargets).
    서버는 이 이름과 정확히 같은 공급대상만 잇고, 이름 맞추기 규칙을 따로 두지 않는다.
    같은 핵심 이름이 괄호 표기로 나뉘면(신혼부부·한부모가족 ↔ 신혼부부·한부모가족(주거약자용)) 경로 이름에 그 괄호 표기가 든
    경로(주거약자용 한부모가족)는 그 대상에만, 나머지 경로는 그렇게 가져간 대상을 빼고 잇는다. 모델이 경로의 소속 대상을 괄호 없이 적어도 갈린다."""
    if data is None:
        return
    names = list(dict.fromkeys(entry.label for _, entry in sheet.entries()
                               if isinstance(entry.label, str) and entry.label.strip()))
    matched = [[name for name in names if _condition_applies(target["supplyTarget"], name)] for target in data["targets"]]
    qualified = [[name for name in candidates if _qualifier(name) and _qualifier(name) in _core_text(target.get("target"))]
                 for target, candidates in zip(data["targets"], matched)]
    claimed = {name for names_ in qualified for name in names_}
    for target, candidates, own in zip(data["targets"], matched, qualified):
        target["supplyTargets"] = own or [name for name in candidates if name not in claimed] or candidates


def _qualifier(name: str) -> str:
    """공급대상 이름의 괄호 표기(신혼부부·한부모가족(주거약자용) → 주거약자용)."""
    found = re.search(r"[(\[]([^)\]]+)", name)
    return _core_text(found.group(1)) if found else ""


def _core_text(value) -> str:
    return re.sub(r"[^0-9A-Za-z가-힣]", "", value) if isinstance(value, str) else ""


def _condition_applies(condition_target, target_name) -> bool:
    """공고 전체 조건은 모든 대상에, 대상 조건은 괄호 앞 핵심 이름이 같은 대상(고령자 ↔ 고령자(주거약자))이나 · 로 묶은 대상의
    한 이름(신혼부부 ↔ 신혼부부·한부모가족)에만 붙인다. 부분 문자열로 맞추면 '신혼부부'가 '예비신혼부부'에, '청년 창업인'이 '청년'에 붙는다."""
    if not _present(condition_target):
        return True
    wanted = _core(condition_target)
    names = {_core(part) for part in re.split(r"[·,/]", target_name)} | {_core(target_name)} if isinstance(target_name, str) else set()
    return bool(wanted) and wanted in names


def _core(value) -> str:
    if not isinstance(value, str):
        return ""
    return re.sub(r"[^0-9A-Za-z가-힣]", "", re.split(r"[(\[]", value, maxsplit=1)[0])


def _joined_within_limit(parts: list[str], own: str | None) -> str:
    """본문 조건을 앞에, 표에 적힌 대상 조건을 맨 뒤에 둔다. 255자(저장 칸 길이)를 넘으면 표 조건은 남기고 본문 조건을 조건 단위로 자른다."""
    tail = [own] if own and own not in parts else []
    budget = _CONDITION_LIMIT - (len(own) + len(_CONDITION_SEPARATOR) if tail else 0)
    kept: list[str] = []
    for part in parts:
        candidate = _CONDITION_SEPARATOR.join([*kept, part])
        if len(candidate) > budget:
            break
        kept.append(part)
    if not kept and not tail and parts:
        return parts[0][:_CONDITION_LIMIT]
    return _CONDITION_SEPARATOR.join([*kept, *tail])[:_CONDITION_LIMIT]


def _present(value) -> bool:
    return isinstance(value, str) and value.strip() != ""


def _merge_complexes(sheet: SupplySheet, supply: list[dict], notice: list[dict]):
    names = []
    for unit in sheet.units:
        add_complex_name(names, unit.complex_name)
    for item in [*supply, *notice]:
        add_complex_name(names, item.get("sourceComplexName"))

    canonical_names = {complex_key(name): name for name in names}
    for unit in sheet.units:
        if isinstance(unit.complex_name, str) and complex_key(unit.complex_name) in canonical_names:
            unit.complex_name = canonical_names[complex_key(unit.complex_name)]

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
