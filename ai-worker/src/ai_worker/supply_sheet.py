"""검산을 마친 공급행 결과(supplyRows·sharedCounts)를 공고 공급표 모양(data.supplySheet)으로 옮기고, 공급행 결과는 출력에서 뺀다.
공급행은 조립과 원문 대조 검산이 쓰는 안쪽 모양이고(모델 답은 supply_answer가 공급행으로 옮긴다), 워커가 내보내는 공급정보는 공급표뿐이다.

공급표는 공고문 표의 칸이 가리키는 단위를 그대로 둔다: 주택형(housingTypes), 공급대상(targets), 주택형×공급대상 칸(cells),
수량 칸 하나가 묶음 하나인 수량 묶음(quotas: WINNER·RESERVE), 금액 칸 한 세트인 임대조건(leaseTerms). 병합된 수량 칸은 멤버가
여럿인 묶음 하나다. 값은 공급행 결과에서 옮길 뿐 새로 읽거나 고치지 않는다.
모집 칸 하나를 함께 쓰려고 묶은 공급행(49 = 49A·49B·49C)은 주택형마다 하나로 나누고 함께 쓰는 수를 수량 묶음으로 둔다
(재설계 결정: 형마다 한 행, 병합 수는 수량 묶음). 서버가 원천 공급정보로 공급표를 만드는 규칙(SourceSupplySheet)도 같은 모양이다.
"""

import re

from ai_worker import checks

EVERYONE = "전체"
_AREA_FIELDS = ("exclusiveArea", "commonArea", "supplyArea", "constructedHouseholdCount")
_AMOUNT_FIELDS = ("rentalDeposit", "monthlyRent", "convertedDeposit")
_KINDS = {"supplyHouseholdCount": "WINNER", "reserveCount": "RESERVE", "totalSupplyHouseholdCount": "WINNER"}
_CELL_COUNT = {"WINNER": "winner", "RESERVE": "reserve"}
_ROW_PATH = re.compile(r"supplyRows\[(\d+|\*)\](.*)")
_OLD_SUPPLY_PATH = re.compile(r"(?:supplyRows|sharedCounts)(?:$|[.\[])")
_TARGET_PATH = re.compile(r"\.targets\[(\d+|\*)\](.*)")
_SUBTYPE_PATH = re.compile(r"\.subtypes(?:\[(\d+|\*)\])?(.*)")


def attach(tree: dict, joined_rows: list[int]) -> None:
    """최종 결과 tree의 공급행 결과를 data.supplySheet로 바꾼다. joined_rows는 assemble이 공급 tree에 남긴 묶은 공급행 번호다
    (supply_assembly.JOINED_ROWS). 묶은 공급행은 주택형마다 나눈다.

    공급행 결과(data.supplyRows, sharedCounts)와 그것을 가리키던 미확인·해당 없음·근거 항목은 결과에서 뺀다. 공급정보의 출력은
    공급표 하나이고, 공급행 경로의 미확인·근거는 공급표 경로로 옮겨 supplySheet 안에 다시 적었다."""
    data = tree["data"]
    data["supplySheet"] = _Sheet(checks.objects(data.get("supplyRows")), set(joined_rows)).build(tree)
    data.pop("supplyRows", None)
    tree.pop("sharedCounts", None)
    for holder, key in ((data, "unresolvedFields"), (tree, "notApplicable"), (tree, "evidence")):
        if isinstance(holder.get(key), list):
            holder[key] = [item for item in holder[key] if not _supply_row_path(item)]


def _supply_row_path(item: object) -> bool:
    path = item.get("path") if isinstance(item, dict) else None
    return isinstance(path, str) and _OLD_SUPPLY_PATH.match(path) is not None


class _Sheet:
    def __init__(self, rows: list[dict], joined: set[int]):
        self.rows = rows
        self.joined = joined
        self.housing_types: list[dict] = []
        self.targets: list[dict] = []
        self.target_keys: dict[tuple[str, str], int] = {}
        self.cells: list[dict] = []
        self.cell_keys: dict[tuple[int, int], int] = {}
        # 칸을 만든 첫 공급행 대상. 칸 하나에 임대조건은 하나이고 그 대상의 금액이다(서버 투영과 같다).
        self.cell_owner: dict[int, tuple[int, int]] = {}
        self.quotas: list[dict] = []
        self.lease_terms: list[dict] = []
        # 옛 경로가 가리키던 공급행·대상이 공급표의 무엇이 됐는지
        self.row_types: dict[int, list[int]] = {}
        self.subtype_type: dict[tuple[int, int], int] = {}
        self.row_cells: dict[int, list[int]] = {}
        self.target_cells: dict[tuple[int, int], list[int]] = {}
        self.target_of: dict[tuple[int, int], int] = {}
        self.own_quota: dict[tuple[int, int, str], int] = {}
        self.shared_quota: dict[int, int] = {}
        self.rest_quota: dict[int, int] = {}
        self.lease_of: dict[tuple[int, int], int] = {}

    def build(self, tree: dict) -> dict:
        for index, row in enumerate(self.rows):
            self._housing_types(index, row)
            self._cells(index, row)
        shared_total_rows = self._shared_quotas(checks.objects(tree.get("sharedCounts")))
        for index, row in enumerate(self.rows):
            self._own_quotas(index, row)
        for index, row in enumerate(self.rows):
            if index not in shared_total_rows:
                self._rest_quota(index, row)
            self._lease_terms(index, row)
        self._link_eligibility(tree["data"].get("eligibility"))
        return {
            "housingTypes": self.housing_types,
            "targets": self.targets,
            "cells": [{"housingType": self._type_key(cell["type"]), "target": self._target_key(cell["target"])}
                      for cell in self.cells],
            "quotas": [{"kind": quota["kind"], "count": quota["count"], "members": self._members(quota["cells"])}
                       for quota in self.quotas],
            "leaseTerms": [{**{field: lease[field] for field in _AMOUNT_FIELDS}, "members": self._members(lease["cells"])}
                           for lease in self.lease_terms],
            "unresolvedFields": self._unresolved(checks.objects(tree["data"].get("unresolvedFields"))),
            "evidence": self._evidence(checks.objects(tree.get("evidence")), checks.objects(tree.get("sharedCounts"))),
        }

    # ---------- 주택형·공급대상·칸 ----------

    def _housing_types(self, index: int, row: dict) -> None:
        subtypes = checks.objects(row.get("subtypes"))
        common = {
            "complexName": (row.get("complexReference") or {}).get("sourceComplexName"),
            "expectedMoveInMonth": row.get("expectedMoveInMonth"),
            "sourceRow": row.get("sourceRow"),
        }
        if index in self.joined and subtypes:
            # 모집 칸을 함께 쓰는 주택형(49A·49B·49C)은 주택형마다 하나다. 함께 쓰는 수는 멤버가 여럿인 수량 묶음이 담는다.
            keys = []
            for subtype_index, subtype in enumerate(subtypes):
                keys.append(self._add_type({**common, "name": subtype.get("name"),
                                            **{field: subtype.get(field) for field in _AREA_FIELDS}, "houseTypes": []}))
                self.subtype_type[(index, subtype_index)] = keys[-1]
            self.row_types[index] = keys
            return
        # 주택형(신청형) 하나가 단지 주택타입 여럿을 묶으면(84 = 84A·84B) 주택형은 하나이고 주택타입은 면적 목록으로 둔다.
        house_types = [{"name": subtype.get("name"), **{field: subtype.get(field) for field in _AREA_FIELDS}}
                       for subtype in subtypes]
        self.row_types[index] = [self._add_type({**common, "name": row.get("sourceHousingTypeName"),
                                                 **{field: row.get(field) for field in _AREA_FIELDS},
                                                 "houseTypes": house_types})]

    def _add_type(self, values: dict) -> int:
        self.housing_types.append({"key": f"h{len(self.housing_types)}", "complexName": values["complexName"],
                                   "name": values["name"], **{field: values[field] for field in _AREA_FIELDS},
                                   "expectedMoveInMonth": values["expectedMoveInMonth"],
                                   "sourceRow": values["sourceRow"], "houseTypes": values["houseTypes"]})
        return len(self.housing_types) - 1

    def _target(self, label: str, condition: str) -> int:
        """같은 표기라도 신청 조건이 다르면 다른 공급대상이다. 표기를 원문에서 확인하지 못한 대상도 버리지 않고 빈 표기로 둔다."""
        key = (label, condition)
        if key not in self.target_keys:
            self.target_keys[key] = len(self.targets)
            self.targets.append({"key": f"t{len(self.targets)}", "label": label, "applicationCondition": condition or None})
        return self.target_keys[key]

    def _cell(self, type_index: int, target_index: int) -> int:
        key = (type_index, target_index)
        if key not in self.cell_keys:
            self.cell_keys[key] = len(self.cells)
            self.cells.append({"type": type_index, "target": target_index})
        return self.cell_keys[key]

    def _cells(self, index: int, row: dict) -> None:
        targets = checks.objects(row.get("targets"))
        row_cells: list[int] = []
        for target_index, target in enumerate(targets):
            target_at = self._target(_text(target.get("target")), _text(target.get("applicationCondition")))
            self.target_of[(index, target_index)] = target_at
            cells = [self._cell(type_index, target_at) for type_index in self.row_types[index]]
            for cell in cells:
                self.cell_owner.setdefault(cell, (index, target_index))
            self.target_cells[(index, target_index)] = cells
            row_cells.extend(cell for cell in cells if cell not in row_cells)
        if not targets:
            # 대상 구분이 없는 표다. 수량 묶음은 칸을 멤버로 가지므로 '전체' 칸 하나를 둔다.
            everyone = self._target(EVERYONE, "")
            row_cells = [self._cell(type_index, everyone) for type_index in self.row_types[index]]
        self.row_cells[index] = row_cells

    # ---------- 수량 묶음 ----------

    def _add_quota(self, kind: str, count: int, cells: list[int]) -> int:
        self.quotas.append({"kind": kind, "count": count, "cells": list(dict.fromkeys(cells))})
        return len(self.quotas) - 1

    def _shared_quotas(self, shared_counts: list[dict]) -> set[int]:
        """여러 대상·공급행이 함께 쓰는 수량 칸 하나가 묶음 하나다. 여러 공급행의 모집수를 함께 쓰는 공급행 번호를 돌려준다."""
        total_rows: set[int] = set()
        for shared_index, shared in enumerate(shared_counts):
            count = shared.get("count")
            cells: list[int] = []
            kinds = set()
            for path in shared.get("paths") or []:
                match = re.fullmatch(r"supplyRows\[(\d+)\](?:\.targets\[(\d+)\])?\.(\w+)", path) \
                    if isinstance(path, str) else None
                if not match or match.group(3) not in _KINDS or int(match.group(1)) not in self.row_cells:
                    continue
                row, target, field = int(match.group(1)), match.group(2), match.group(3)
                kinds.add(_KINDS[field])
                if target is None:
                    total_rows.add(row)
                    cells.extend(self.row_cells[row])
                else:
                    cells.extend(self.target_cells.get((row, int(target)), []))
            if _integer(count) and len(kinds) == 1 and cells:
                self.shared_quota[shared_index] = self._add_quota(kinds.pop(), count, cells)
        return total_rows

    def _own_quotas(self, index: int, row: dict) -> None:
        for target_index, target in enumerate(checks.objects(row.get("targets"))):
            for field in ("supplyHouseholdCount", "reserveCount"):
                count = target.get(field)
                if not _integer(count):
                    continue
                kind = _KINDS[field]
                cells = self.target_cells[(index, target_index)]
                # 같은 행에 같은 공급대상이 두 번 나오면 칸은 하나이고 수는 더한다(서버 투영과 같다).
                same = next((at for at, quota in enumerate(self.quotas)
                             if at in self.own_quota.values() and quota["kind"] == kind and quota["cells"] == cells), None)
                if same is None:
                    same = self._add_quota(kind, count, cells)
                else:
                    self.quotas[same]["count"] += count
                self.own_quota[(index, target_index, kind)] = same

    def _rest_quota(self, index: int, row: dict) -> None:
        """공급행 모집수 중 대상별 수로 나뉘지 않은 나머지는 그 행의 모든 칸이 함께 쓴다. 대상별 수의 합이 행 모집수보다 크면
        행 모집수를 버린다. 칸의 대상이 하나뿐이면 '전체' 칸을 더해 나머지가 그 대상의 수로 읽히지 않게 한다."""
        total = row.get("totalSupplyHouseholdCount")
        if not _integer(total):
            return
        own = set(self.row_cells[index])
        counted = sum(quota["count"] for quota in self.quotas
                      if quota["kind"] == "WINNER" and quota["cells"] and set(quota["cells"]) <= own)
        if not (total > counted or total == counted == 0):
            return
        labels = {self.cells[cell]["target"] for cell in self.row_cells[index]}
        everyone = self.target_keys.get((EVERYONE, ""))
        if len(labels) == 1 and labels != {everyone}:
            everyone = self._target(EVERYONE, "")
            self.row_cells[index] += [self._cell(type_index, everyone) for type_index in self.row_types[index]]
        self.rest_quota[index] = self._add_quota("WINNER", total - counted, self.row_cells[index])

    def _lease_terms(self, index: int, row: dict) -> None:
        """금액이 하나라도 있는 칸마다 임대조건 하나이고, 그 칸을 만든 첫 대상의 금액이다. 같은 행에 같은 대상이 다시 나와도
        임대조건을 더 두지 않는다. 금액이 같아도 합치지 않는다(한 곳을 고칠 때 다른 단지 금액이 바뀐다)."""
        for target_index, target in enumerate(checks.objects(row.get("targets"))):
            cells = self.target_cells[(index, target_index)]
            if any(self.cell_owner[cell] != (index, target_index) for cell in cells):
                continue
            if all(target.get(field) is None for field in _AMOUNT_FIELDS):
                continue
            self.lease_terms.append({**{field: target.get(field) for field in _AMOUNT_FIELDS}, "cells": cells})
            self.lease_of[(index, target_index)] = len(self.lease_terms) - 1

    # ---------- 신청자격 연결 ----------

    def _link_eligibility(self, eligibility: dict | None) -> None:
        """신청자격 경로가 가리키는 공급대상 이름(supplyTargets)을 공급표 대상 키로도 싣는다. 서버 규칙과 같이 공백만 무시하고
        표기가 같은 대상을 모두 잇는다(같은 표기의 신청 조건 다른 대상 포함). 이름이 없으면 키도 없고 서버는 모든 경로를 잇는다."""
        if not isinstance(eligibility, dict):
            return
        for target in checks.objects(eligibility.get("targets")):
            names = {_squash(name) for name in target.get("supplyTargets") or [] if isinstance(name, str) and name.strip()}
            target["supplyTargetKeys"] = [value["key"] for value in self.targets if _squash(value["label"]) in names]

    # ---------- 미확인·근거 경로 ----------

    def _unresolved(self, findings: list[dict]) -> list[dict]:
        result = []
        for finding in findings:
            for path in self._paths(finding.get("path")):
                result.append({**finding, "path": path})
        return checks.deduplicate(result)

    def _evidence(self, evidence: list[dict], shared_counts: list[dict]) -> list[dict]:
        result = []
        for item in evidence:
            for path in self._paths(item.get("path")):
                result.append({**item, "path": path})
        # 수량 묶음·임대조건 전체의 근거는 그 값을 낸 대상·공급행의 근거다.
        by_path = {}
        for item in evidence:
            by_path.setdefault(item.get("path"), []).append(item)
        for (row, target, _), quota in self.own_quota.items():
            result += [{**item, "path": f"quotas[{quota}]"} for item in by_path.get(f"supplyRows[{row}].targets[{target}]", [])]
        for row, quota in self.rest_quota.items():
            result += [{**item, "path": f"quotas[{quota}]"} for item in by_path.get(f"supplyRows[{row}]", [])]
        for (row, target), lease in self.lease_of.items():
            result += [{**item, "path": f"leaseTerms[{lease}]"}
                       for item in by_path.get(f"supplyRows[{row}].targets[{target}]", [])]
        for shared_index, quota in self.shared_quota.items():
            shared = shared_counts[shared_index]
            if shared.get("page") is not None and shared.get("quote"):
                result.append({"path": f"quotas[{quota}]", "page": shared["page"], "quote": shared["quote"]})
        return checks.deduplicate(result)

    def _paths(self, path: object) -> list[str]:
        """공급행 결과의 경로(supplyRows[0].targets[1].reserveCount)가 가리키던 것을 공급표 경로로 옮긴다. 공급행 밖의 경로는 없다."""
        match = _ROW_PATH.fullmatch(path) if isinstance(path, str) else None
        if not match:
            return []
        rows = range(len(self.rows)) if match.group(1) == "*" else [int(match.group(1))]
        paths = []
        for row in rows:
            if row in self.row_types:
                paths += self._row_paths(row, match.group(2))
        return list(dict.fromkeys(paths))

    def _row_paths(self, row: int, rest: str) -> list[str]:
        types = [f"housingTypes[{at}]" for at in self.row_types[row]]
        if target := _TARGET_PATH.fullmatch(rest):
            targets = range(len(checks.objects(self.rows[row].get("targets")))) if target.group(1) == "*" \
                else [int(target.group(1))]
            return [path for at in targets if (row, at) in self.target_cells
                    for path in self._target_paths(row, at, target.group(2))]
        if rest.startswith(".targets"):
            return [f"cells[{cell}]" for cell in self.row_cells[row]]
        if subtype := _SUBTYPE_PATH.fullmatch(rest):
            return self._subtype_paths(row, subtype.group(1), subtype.group(2))
        field = rest.lstrip(".")
        if field == "totalSupplyHouseholdCount":
            if row in self.rest_quota:
                return [f"quotas[{self.rest_quota[row]}].count"]
            return [f"cells[{cell}].winner" for cell in self.row_cells[row]]
        renamed = {"sourceHousingTypeName": "name", "complexReference.sourceComplexName": "complexName",
                   "complexReference": "complexName"}.get(field, field)
        return [f"{path}.{renamed}" if renamed else path for path in types]

    def _target_paths(self, row: int, target: int, rest: str) -> list[str]:
        field = rest.lstrip(".")
        cells = self.target_cells[(row, target)]
        if field in ("target", "applicationCondition"):
            name = "label" if field == "target" else field
            return [f"targets[{self.target_of[(row, target)]}].{name}"]
        if field in ("supplyHouseholdCount", "reserveCount"):
            kind = _KINDS[field]
            quota = self.own_quota.get((row, target, kind))
            return [f"quotas[{quota}].count"] if quota is not None \
                else [f"cells[{cell}].{_CELL_COUNT[kind]}" for cell in cells]
        if field in _AMOUNT_FIELDS:
            lease = self.lease_of.get((row, target))
            return [f"leaseTerms[{lease}].{field}"] if lease is not None else [f"cells[{cell}].{field}" for cell in cells]
        return [f"cells[{cell}]" for cell in cells]

    def _subtype_paths(self, row: int, subtype: str | None, rest: str) -> list[str]:
        field = rest.lstrip(".")
        if row in self.joined and any(key[0] == row for key in self.subtype_type):
            types = [at for (owner, index), at in self.subtype_type.items()
                     if owner == row and (subtype in (None, "*") or index == int(subtype))]
            return [f"housingTypes[{at}]" + (f".{field}" if field else "") for at in types]
        house = "houseTypes" if subtype is None else f"houseTypes[{subtype}]"
        return [f"housingTypes[{at}].{house}" + (f".{field}" if field else "") for at in self.row_types[row]]

    # ---------- 출력 ----------

    def _type_key(self, index: int) -> str:
        return self.housing_types[index]["key"]

    def _target_key(self, index: int) -> str:
        return self.targets[index]["key"]

    def _members(self, cells: list[int]) -> list[dict]:
        return [{"housingType": self._type_key(self.cells[cell]["type"]),
                 "target": self._target_key(self.cells[cell]["target"])} for cell in cells]


def _text(value: object) -> str:
    return value.strip() if isinstance(value, str) else ""


def _squash(value: str) -> str:
    return "".join(value.split())


def _integer(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)
