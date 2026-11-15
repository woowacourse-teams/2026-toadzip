"""공고 공급표(data.supplySheet)의 안쪽 모델과 출력.

조립(supply_assembly)이 원문 칸과 대조한 값을 모집 단위(Unit)와 그 공급대상(Entry), 여러 칸이 함께 쓰는 수량(Shared)으로 둔다.
merge·checks·source_frame은 이 모델 위에서 신청 조건·검산·GH 틀 식별자를 다루고, 마지막에 emit이 공고문 표의 칸 단위 그대로
내보낸다: 주택형(housingTypes), 공급대상(targets), 주택형×공급대상 칸(cells), 수량 칸 하나가 묶음 하나인 수량 묶음
(quotas: WINNER·RESERVE), 금액 칸 한 세트인 임대조건(leaseTerms). 미확인·근거는 단위·대상·주택형에 달려 있다가 출력할 때
공급표 경로가 된다. 서버가 원천 공급정보로 공급표를 만드는 규칙(SourceSupplySheet)도 같은 모양이다.
"""

from dataclasses import dataclass, field

from ai_worker import checks, target_groups

EVERYONE = "전체"
AREA_FIELDS = ("exclusiveArea", "commonArea", "supplyArea", "constructedHouseholdCount")
AMOUNT_FIELDS = ("rentalDeposit", "monthlyRent", "convertedDeposit")
# 단위의 모집수. 대상별 수로 나뉘지 않은 나머지는 그 단위 모든 칸이 함께 쓰는 수량 묶음이다.
TOTAL = "totalSupplyHouseholdCount"
COUNT_KINDS = {"supplyHouseholdCount": "WINNER", "reserveCount": "RESERVE", TOTAL: "WINNER"}
_CELL_COUNT = {"WINNER": "winner", "RESERVE": "reserve"}


@dataclass(eq=False)
class HousingType:
    """모집 칸 하나를 함께 쓰는 단위(49A·49B·49C의 모집 70)에 든 주택형 하나. areas는 AREA_FIELDS 값이다."""

    name: object
    areas: dict


@dataclass(eq=False)
class Entry:
    """모집 단위의 공급대상 하나. 단위의 주택형마다 칸(주택형×공급대상) 하나를 차지한다. label은 원문에서 확인한 표기
    (확인하지 못하면 비어 있다), values는 대상 모집수(supplyHouseholdCount)·예비자 수(reserveCount)·금액(AMOUNT_FIELDS)·
    신청 조건(applicationCondition)이다."""

    label: object
    values: dict


@dataclass(eq=False)
class Unit:
    """모집 단위: 모집수를 정하는 주택형과 그 공급대상들. 보통 주택형 하나이고, 모집 칸 하나를 함께 쓰는 주택형들은
    주택형 여럿(parts)인 단위 하나다. name은 단위 표기로, 주택형이 여럿이면 공통 형명(49)이다. values는 모집수(TOTAL)·면적
    (AREA_FIELDS)·입주 예정 연월(expectedMoveInMonth)이다. house_types는 주택형 하나가 묶은 단지 주택타입(84 = 84A·84B)의
    이름과 면적, source_row는 GH 공급행 틀 행 식별자다. place는 이름이 같은 다른 단위와 경고에서 가릴 원문 자리(원본 2쪽 표 1의 4행)로,
    이름이 겹칠 때만 있다."""

    complex_name: object
    name: object
    values: dict
    entries: list[Entry] = field(default_factory=list)
    house_types: list[dict] = field(default_factory=list)
    parts: list[HousingType] = field(default_factory=list)
    source_row: str | None = None
    place: str | None = None

    def areas(self) -> list[object]:
        """단위 면적과 그 주택형·주택타입의 전용면적들. LH 검산이 쓴다."""
        return [self.values.get("exclusiveArea"),
                *(part.areas.get("exclusiveArea") for part in self.parts),
                *(house.get("exclusiveArea") for house in self.house_types)]

    def house_type_count(self) -> int:
        """단위가 묶은 주택타입 수. 주택타입 이름은 같은 면적의 타입을 '·'로 잇는다(84A·84C1·84C2)."""
        names = [part.name for part in self.parts] + [house.get("name") for house in self.house_types]
        return sum(len(str(name or "").split("·")) for name in names)

    def label(self) -> str:
        """경고에서 관리자가 알아볼 이름(단지 주택형)."""
        words = [text for text in (_text(self.complex_name), _text(self.name)) if text]
        name = " ".join(words) or "이름을 확인하지 못한 주택형"
        return f"{name}({self.place})" if self.place else name


@dataclass(eq=False)
class Shared:
    """여러 칸이 함께 쓰는 수량 칸 하나(병합 칸). owners의 단위는 모집수를, 공급대상은 kind 수를 함께 쓴다.
    kind가 None이면 수나 종류를 하나로 읽지 못한 칸이라 묶음을 만들지 않지만, 그 단위의 모집수는 이 칸이 정한다.
    page·quote는 그 칸이 적힌 원문 행의 근거다."""

    kind: str | None
    count: object
    owners: list
    page: object
    quote: object


@dataclass(frozen=True)
class Finding:
    """단위·대상·주택형의 미확인 하나. field가 비면 그 단위·대상·주택형 전체다."""

    owner: object
    field: str
    reason: str


@dataclass(frozen=True)
class Cite:
    """단위·대상·주택형(빈 field) 또는 그 값 하나의 근거."""

    owner: object
    field: str
    page: object
    quote: object


@dataclass(eq=False)
class SupplySheet:
    units: list[Unit] = field(default_factory=list)
    shared: list[Shared] = field(default_factory=list)
    findings: list[Finding] = field(default_factory=list)
    evidence: list[Cite] = field(default_factory=list)

    def entries(self) -> list[tuple[Unit, Entry]]:
        return [(unit, entry) for unit in self.units for entry in unit.entries]

    def label(self, owner: object) -> str:
        """경고에서 관리자가 알아볼 단위·대상·주택형 이름(소사벌 B-2 84A 청년)."""
        for unit in self.units:
            if owner is unit:
                return unit.label()
            if any(owner is part for part in unit.parts):
                return " ".join(text for text in (_text(unit.complex_name), _text(owner.name)) if text) or unit.label()
            if any(owner is entry for entry in unit.entries):
                return f"{unit.label()} {_text(owner.label) or '표기를 확인하지 못한 공급대상'}"
        return "공급표"

    def emit(self, eligibility: dict | None) -> dict:
        """공급표를 결과 모양으로 내보내고, 신청자격 경로마다 같은 표기의 공급표 대상 키(supplyTargetKeys)를 싣는다."""
        return _Emit(self).build(eligibility)


class _Emit:
    def __init__(self, sheet: SupplySheet):
        self.sheet = sheet
        self.housing_types: list[dict] = []
        self.targets: list[dict] = []
        self.target_keys: dict[tuple[str, str], int] = {}
        self.cells: list[dict] = []
        self.cell_keys: dict[tuple[int, int], int] = {}
        # 칸을 만든 첫 대상. 칸 하나에 임대조건은 하나이고 그 대상의 금액이다(서버 투영과 같다).
        self.cell_owner: dict[int, Entry] = {}
        self.quotas: list[dict] = []
        self.lease_terms: list[dict] = []
        # 단위·대상·주택형이 공급표의 무엇이 됐는지
        self.unit_types: dict[Unit, list[int]] = {}
        self.part_type: dict[HousingType, int] = {}
        self.unit_cells: dict[Unit, list[int]] = {}
        self.entry_cells: dict[Entry, list[int]] = {}
        self.target_of: dict[Entry, int] = {}
        self.own_quota: dict[tuple[Entry, str], int] = {}
        self.shared_quota: dict[Shared, int] = {}
        self.rest_quota: dict[Unit, int] = {}
        self.lease_of: dict[Entry, int] = {}

    def build(self, eligibility: dict | None) -> dict:
        units = self.sheet.units
        for unit in units:
            self._housing_types(unit)
            self._cells(unit)
        shared_total_units = self._shared_quotas()
        for unit in units:
            self._own_quotas(unit)
        for unit in units:
            if unit not in shared_total_units:
                self._rest_quota(unit)
            self._lease_terms(unit)
        self._link_eligibility(eligibility)
        return {
            "housingTypes": self.housing_types,
            "targets": self.targets,
            "cells": [{"housingType": self._type_key(cell["type"]), "target": self._target_key(cell["target"])}
                      for cell in self.cells],
            "quotas": [{"kind": quota["kind"], "count": quota["count"], "members": self._members(quota["cells"])}
                       for quota in self.quotas],
            "leaseTerms": [{**{name: lease[name] for name in AMOUNT_FIELDS}, "members": self._members(lease["cells"])}
                           for lease in self.lease_terms],
            "unresolvedFields": checks.deduplicate([{"path": path, "reason": finding.reason}
                                                    for finding in self.sheet.findings
                                                    for path in self._paths(finding.owner, finding.field)]),
            "evidence": self._evidence(),
        }

    # ---------- 주택형·공급대상·칸 ----------

    def _housing_types(self, unit: Unit) -> None:
        # 모집 칸을 함께 쓰는 주택형(49A·49B·49C)은 주택형마다 하나다. 함께 쓰는 수는 멤버가 여럿인 수량 묶음이 담는다.
        # 주택형 하나가 단지 주택타입 여럿을 묶으면(84 = 84A·84B) 주택형은 하나이고 주택타입은 면적 목록으로 둔다.
        common = {"complexName": unit.complex_name, "expectedMoveInMonth": unit.values.get("expectedMoveInMonth"),
                  "sourceRow": unit.source_row}
        if unit.parts:
            keys = []
            for part in unit.parts:
                self.part_type[part] = self._add_type(common, part.name, part.areas, [])
                keys.append(self.part_type[part])
            self.unit_types[unit] = keys
            return
        house_types = [{"name": house.get("name"), **{name: house.get(name) for name in AREA_FIELDS}}
                       for house in unit.house_types]
        self.unit_types[unit] = [self._add_type(common, unit.name, unit.values, house_types)]

    def _add_type(self, common: dict, name: object, areas: dict, house_types: list[dict]) -> int:
        self.housing_types.append({"key": f"h{len(self.housing_types)}", "complexName": common["complexName"],
                                   "name": name, **{field_name: areas.get(field_name) for field_name in AREA_FIELDS},
                                   "expectedMoveInMonth": common["expectedMoveInMonth"],
                                   "sourceRow": common["sourceRow"], "houseTypes": house_types})
        return len(self.housing_types) - 1

    def _target(self, label: str, condition: str) -> int:
        """같은 표기라도 신청 조건이 다르면 다른 공급대상이다. 표기를 원문에서 확인하지 못한 대상도 버리지 않고 빈 표기로 둔다."""
        key = (label, condition)
        if key not in self.target_keys:
            self.target_keys[key] = len(self.targets)
            self.targets.append({"key": f"t{len(self.targets)}", "label": label, "applicationCondition": condition or None,
                                 "groups": target_groups.groups(label)})
        return self.target_keys[key]

    def _cell(self, type_index: int, target_index: int) -> int:
        key = (type_index, target_index)
        if key not in self.cell_keys:
            self.cell_keys[key] = len(self.cells)
            self.cells.append({"type": type_index, "target": target_index})
        return self.cell_keys[key]

    def _cells(self, unit: Unit) -> None:
        unit_cells: list[int] = []
        for entry in unit.entries:
            target_at = self._target(_text(entry.label).strip(), _text(entry.values.get("applicationCondition")).strip())
            self.target_of[entry] = target_at
            cells = [self._cell(type_index, target_at) for type_index in self.unit_types[unit]]
            for cell in cells:
                self.cell_owner.setdefault(cell, entry)
            self.entry_cells[entry] = cells
            unit_cells.extend(cell for cell in cells if cell not in unit_cells)
        if not unit.entries:
            # 대상 구분이 없는 표다. 수량 묶음은 칸을 멤버로 가지므로 '전체' 칸 하나를 둔다.
            everyone = self._target(EVERYONE, "")
            unit_cells = [self._cell(type_index, everyone) for type_index in self.unit_types[unit]]
        self.unit_cells[unit] = unit_cells

    # ---------- 수량 묶음 ----------

    def _add_quota(self, kind: str, count: int, cells: list[int]) -> int:
        self.quotas.append({"kind": kind, "count": count, "cells": list(dict.fromkeys(cells))})
        return len(self.quotas) - 1

    def _shared_quotas(self) -> set[Unit]:
        """여러 대상·단위가 함께 쓰는 수량 칸 하나가 묶음 하나다. 모집수를 함께 쓰는 칸이 정한 단위를 돌려준다."""
        total_units: set[Unit] = set()
        for shared in self.sheet.shared:
            cells: list[int] = []
            for owner in shared.owners:
                if isinstance(owner, Unit):
                    total_units.add(owner)
                    cells.extend(self.unit_cells[owner])
                else:
                    cells.extend(self.entry_cells[owner])
            if shared.kind and _integer(shared.count) and cells:
                self.shared_quota[shared] = self._add_quota(shared.kind, shared.count, cells)
        return total_units

    def _own_quotas(self, unit: Unit) -> None:
        for entry in unit.entries:
            for name in ("supplyHouseholdCount", "reserveCount"):
                count = entry.values.get(name)
                if not _integer(count):
                    continue
                kind = COUNT_KINDS[name]
                cells = self.entry_cells[entry]
                # 같은 행에 같은 공급대상이 두 번 나오면 칸은 하나이고 수는 더한다(서버 투영과 같다).
                same = next((at for at, quota in enumerate(self.quotas)
                             if at in self.own_quota.values() and quota["kind"] == kind and quota["cells"] == cells), None)
                if same is None:
                    same = self._add_quota(kind, count, cells)
                else:
                    self.quotas[same]["count"] += count
                self.own_quota[(entry, kind)] = same

    def _rest_quota(self, unit: Unit) -> None:
        """단위 모집수 중 대상별 수로 나뉘지 않은 나머지는 그 단위의 모든 칸이 함께 쓴다. 대상별 수의 합이 단위 모집수보다 크면
        단위 모집수를 버린다. 칸의 대상이 하나뿐이면 '전체' 칸을 더해 나머지가 그 대상의 수로 읽히지 않게 한다."""
        total = unit.values.get(TOTAL)
        if not _integer(total):
            return
        own = set(self.unit_cells[unit])
        counted = sum(quota["count"] for quota in self.quotas
                      if quota["kind"] == "WINNER" and quota["cells"] and set(quota["cells"]) <= own)
        if not (total > counted or total == counted == 0):
            return
        labels = {self.cells[cell]["target"] for cell in self.unit_cells[unit]}
        everyone = self.target_keys.get((EVERYONE, ""))
        if len(labels) == 1 and labels != {everyone}:
            everyone = self._target(EVERYONE, "")
            self.unit_cells[unit] += [self._cell(type_index, everyone) for type_index in self.unit_types[unit]]
        self.rest_quota[unit] = self._add_quota("WINNER", total - counted, self.unit_cells[unit])

    def _lease_terms(self, unit: Unit) -> None:
        """금액이 하나라도 있는 칸마다 임대조건 하나이고, 그 칸을 만든 첫 대상의 금액이다. 같은 행에 같은 대상이 다시 나와도
        임대조건을 더 두지 않는다. 금액이 같아도 합치지 않는다(한 곳을 고칠 때 다른 단지 금액이 바뀐다)."""
        for entry in unit.entries:
            cells = self.entry_cells[entry]
            if any(self.cell_owner[cell] is not entry for cell in cells):
                continue
            if all(entry.values.get(name) is None for name in AMOUNT_FIELDS):
                continue
            self.lease_terms.append({**{name: entry.values.get(name) for name in AMOUNT_FIELDS}, "cells": cells})
            self.lease_of[entry] = len(self.lease_terms) - 1

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

    def _evidence(self) -> list[dict]:
        result = [{"path": path, "page": cite.page, "quote": cite.quote}
                  for cite in self.sheet.evidence for path in self._paths(cite.owner, cite.field)]
        # 수량 묶음·임대조건 전체의 근거는 그 값을 낸 대상·단위의 근거다.
        whole: dict[object, list[Cite]] = {}
        for cite in self.sheet.evidence:
            if not cite.field:
                whole.setdefault(cite.owner, []).append(cite)

        def cited(owner: object, path: str) -> list[dict]:
            return [{"path": path, "page": cite.page, "quote": cite.quote} for cite in whole.get(owner, [])]
        for (entry, _), quota in self.own_quota.items():
            result += cited(entry, f"quotas[{quota}]")
        for unit, quota in self.rest_quota.items():
            result += cited(unit, f"quotas[{quota}]")
        for entry, lease in self.lease_of.items():
            result += cited(entry, f"leaseTerms[{lease}]")
        for shared, quota in self.shared_quota.items():
            if shared.page is not None and shared.quote:
                result.append({"path": f"quotas[{quota}]", "page": shared.page, "quote": shared.quote})
        return checks.deduplicate(result)

    def _paths(self, owner: object, name: str) -> list[str]:
        """단위·대상·주택형의 값 하나가 공급표의 어느 자리가 됐는지."""
        if isinstance(owner, Entry):
            return self._entry_paths(owner, name)
        if isinstance(owner, HousingType):
            return [f"housingTypes[{self.part_type[owner]}]" + (f".{name}" if name else "")]
        types = [f"housingTypes[{at}]" for at in self.unit_types[owner]]
        if name == TOTAL:
            if owner in self.rest_quota:
                return [f"quotas[{self.rest_quota[owner]}].count"]
            return [f"cells[{cell}].winner" for cell in self.unit_cells[owner]]
        return [f"{path}.{name}" if name else path for path in types]

    def _entry_paths(self, entry: Entry, name: str) -> list[str]:
        cells = self.entry_cells[entry]
        if name in ("label", "applicationCondition"):
            return [f"targets[{self.target_of[entry]}].{name}"]
        if name in ("supplyHouseholdCount", "reserveCount"):
            kind = COUNT_KINDS[name]
            quota = self.own_quota.get((entry, kind))
            return [f"quotas[{quota}].count"] if quota is not None \
                else [f"cells[{cell}].{_CELL_COUNT[kind]}" for cell in cells]
        if name in AMOUNT_FIELDS:
            lease = self.lease_of.get(entry)
            return [f"leaseTerms[{lease}].{name}"] if lease is not None else [f"cells[{cell}].{name}" for cell in cells]
        return [f"cells[{cell}]" for cell in cells]

    # ---------- 출력 ----------

    def _type_key(self, index: int) -> str:
        return self.housing_types[index]["key"]

    def _target_key(self, index: int) -> str:
        return self.targets[index]["key"]

    def _members(self, cells: list[int]) -> list[dict]:
        return [{"housingType": self._type_key(self.cells[cell]["type"]),
                 "target": self._target_key(self.cells[cell]["target"])} for cell in cells]


def _text(value: object) -> str:
    return value if isinstance(value, str) else ""


def _squash(value: str) -> str:
    return "".join(value.split())


def _integer(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)
