"""칸 초안(Draft: 모델 공급 단계 답을 supply_answer가 옮긴 칸이나 코드가 표에서 읽은 칸)을 공급표 모델과 단지로 조립한다.

같은 단지·주택형 칸은 한 모집 단위(supply_sheet.Unit)의 공급대상(Entry)으로 모으고, 단위·대상마다 그 칸이 있는 원문 표 행을
근거로 둔다. 문자열·수치는 source_check로 같은 의미의 원문 칸과 대조해 확인되지 않으면 그 단위·대상의 미확인으로 남긴다.
여러 주택형 행이 병합된 모집수 칸 하나를 함께 쓰면 그 주택형들은 주택형 여럿(parts)인 모집 단위 하나다.
"""

import re
from dataclasses import dataclass, field as data_field, replace

from ai_worker import checks, pages, tables
from ai_worker.checks import deduplicate
from ai_worker.source_check import (
    Headings, check_source, count_cell, count_or_dash, normalize_source_text, numeric_cell_matches,
    recruitment_count, source_cell_matches, source_texts, target_cells,
)
from ai_worker.supply_sheet import (
    AMOUNT_FIELDS, AREA_FIELDS, COUNT_KINDS, TOTAL, Cite, Entry, Finding, HousingType, Shared, SupplySheet, Unit,
)

_ROW_FIELDS = (TOTAL, *AREA_FIELDS)
_TARGET_VALUE_FIELDS = ("supplyHouseholdCount", "reserveCount", *AMOUNT_FIELDS)
_RESERVE_MISSING = "이번 공고의 예비자 모집수를 표에서 확인하지 못함"


@dataclass(eq=False)
class DraftCell:
    """칸 초안 하나: 공급표의 주택형 하나 × 공급대상 하나(대상 구분이 없는 표는 대상 없이 주택형 하나)와 그 칸에만 적힌 값.
    모델 답(supply_answer)과 코드가 표에서 읽은 행(supply_grouping)이 같은 초안을 만들고, 조립이 원문 칸과 대조한다.
    values는 주택형 값(모집수 totalSupplyHouseholdCount·면적·건설호수·입주 연월)과 칸 값(대상 모집수 supplyHouseholdCount·
    예비자 수 reserveCount·금액·신청 조건)이다. row_ids는 그 값들이 적힌 원문 표 행, unresolved는 읽었지만 하나로 정하지 못한
    값의 (필드, 사유)다. targeted는 대상 칸인지다(표기를 읽지 못해 target이 비어도 대상 칸이다)."""

    row_ids: list[str]
    complex_name: object
    housing_type: object
    target: object = None
    targeted: bool = False
    values: dict = data_field(default_factory=dict)
    house_types: list[dict] = data_field(default_factory=list)
    unresolved: list[tuple[str, str]] = data_field(default_factory=list)


@dataclass(eq=False)
class SharedCount:
    """여러 칸이 함께 쓰는 수량 칸 하나(병합 칸). field가 totalSupplyHouseholdCount면 cells의 주택형들이 모집수를 함께 쓴다
    (주택형마다 칸 하나를 가리킨다). row_ids는 그 수가 적힌 원문 행이다."""

    field: str
    count: object
    cells: list[DraftCell]
    row_ids: list[str]


@dataclass(frozen=True)
class Draft:
    cells: list[DraftCell]
    shared: list[SharedCount]


def assemble(draft: Draft, selected: pages.PromptPages,
             source_names=()) -> tuple[dict, SupplySheet, list[str]]:
    """칸 초안을 표 셀 근거에 맞춰 공급표 모델로 조립한다. 단지와 그 미확인·근거는 관리자 계약 tree로, 원문 대조 검산의 경고를
    함께 돌려준다.
    source_names는 LH 공급행의 단지명이다. 표 위 블록 제목과 단지명을 대조할 때 후보로 쓴다(Headings)."""
    return _Assembly(draft, selected, source_names).build()


class _Assembly:
    """조립 중인 공급표. 단위·대상마다 원문 행과 원문 칸에서 정한 값을 곁에 두고, 미확인·근거는 그 단위·대상에 바로 단다."""

    def __init__(self, draft: Draft, selected: pages.PromptPages, source_names):
        self.draft = draft
        self.selected = selected
        self.per_house_type_tables = _per_house_type_tables(draft.cells, selected)
        self.headings = Headings(selected.supply_row_headings, tuple(
            name for name in (*(item.complex_name for item in draft.cells), *source_names) if isinstance(name, str)))
        self.units: list[Unit] = []
        # 단위·대상의 값이 적힌 원문 표 행
        self.rows: dict[Unit | Entry, set[str]] = {}
        # 원문 칸 하나와 같지 않지만 코드가 원문 칸들에서 정한 값(합·예비자 수·공통 형명). 원문 대조가 확인된 값으로 본다.
        # None은 그 필드의 어떤 값도 확인된 값으로 보지 않게 한다.
        self.confirmed: dict[Unit | Entry, dict] = {}
        # 주택타입 여럿을 묶은 주택형 단위의 주택타입 행 수량 합(_fill_block_counts)
        self.block_counts: dict[Unit, dict] = {}
        # 단위가 가리키는 주택형 행에서 표의 주택타입 열로 읽은 세부 타입. 모델이 낸 세부 타입(Unit.house_types) 대신 쓴다.
        self.table_subtypes: dict[Unit, list[tables.TableSubtype]] = {}
        self.unit_of: dict[DraftCell, Unit] = {}
        self.entry_of: dict[DraftCell, Entry | None] = {}
        # 모집 칸 하나를 함께 쓰는 주택형들을 묶은 단위
        self.joined: set[Unit] = set()
        # 원문 칸에서 코드가 정한 여러 대상의 공통 수량: (필드, 수, 대상들, 원문 행)
        self.code_shared: list[tuple[str, int, list[Entry], list[str]]] = []
        self.findings: list[Finding] = []
        self.cites: list[Cite] = []
        self.shared: list[Shared] = []

    def build(self) -> tuple[dict, SupplySheet, list[str]]:
        keys: dict[tuple, Unit] = {}
        identities: dict[Unit, list[tuple[tuple, Entry]]] = {}
        for index, item in enumerate(self.draft.cells):
            self._place(index, item, keys, identities)
        self._join_units_sharing_counts()
        for unit in self.units:
            self._area_from_source_cells(unit)
            if unit in self.joined:
                self._joined_counts(unit)
            else:
                self._counts_from_source_cells(unit)
            self._leave_merged_cells_unconfirmed(unit)
        for unit in self.units:
            self._finish(unit)
        tree = self._complexes()
        self._shared_counts()
        failures = self._check_source()
        for unit in self.units:
            self._all_applicants_when_no_target_column(unit)
        self._tell_apart_same_names()
        sheet = SupplySheet(self.units, self.shared, list(dict.fromkeys(self.findings)), self.cites)
        warnings = [f"{sheet.label(owner)}: {reason}" for owner, reason in failures]
        return tree, sheet, warnings

    # ---------- 미확인 ----------

    def _find(self, owner: object, name: str, reason: str | None) -> None:
        if reason is not None:
            self.findings.append(Finding(owner, name, reason))

    def _drop(self, owner: object, name: str, reason: str | None = None) -> None:
        """원문 칸에서 값을 정해 그 값의 미확인을 지운다. reason이 있으면 그 사유의 미확인만 지운다."""
        self.findings = [finding for finding in self.findings if not (
            finding.owner is owner and finding.field == name and reason in (None, finding.reason))]

    def _confirm(self, owner: Unit | Entry, **values: int | str | None) -> None:
        """원문 칸 하나와 같지 않은 값(합·예비자 수)을 원문 대조가 확인된 값으로 보게 둔다. None은 그 필드의 어떤 값도
        확인된 값으로 보지 않게 한다."""
        self.confirmed.setdefault(owner, {}).update(values)

    # ---------- 칸을 모집 단위·공급대상으로 ----------

    def _place(self, index: int, item: DraftCell, keys: dict[tuple, Unit],
               identities: dict[Unit, list[tuple[tuple, Entry]]]) -> None:
        """칸 하나를 그 단지·주택형 모집 단위의 공급대상으로 둔다. 원문에서 확인하지 못한 이름도 같은 원문 칸이나 같은 표기면
        한 단위로 모은다. 칸마다 따로 두면 같은 표 행이 대상 수만큼 단위로 중복된다(사천벌리1에서 LH 행마다 같은 행이 나온 경우)."""
        selected = self.selected
        row_ids = list(dict.fromkeys(item.row_ids))
        complex_reason = None
        if self.headings.confirms(item.complex_name, row_ids, selected.supply_row_values):
            complex_name = _normalize_source_display(item.complex_name, "complexName")
        else:
            complex_name, complex_reason = _source_text(item.complex_name, "complexName", row_ids, selected, "")
        house_type_unit = _house_type_unit(row_ids, selected, self.per_house_type_tables)
        if house_type_unit is None:
            housing_type, type_reason = _source_text(item.housing_type, "housingType", row_ids, selected, None)
        else:
            housing_type, type_reason = house_type_unit.label(item.housing_type)
        # 대상 열이 없는 표에서 모델이 target을 빠뜨려도 대상 값(예비자 수·보증금)이 있으면 대상 하나다. 버리면 그 값이 사라진다.
        has_target = item.targeted or any(item.values.get(name) is not None
                                          for name in (*_TARGET_VALUE_FIELDS, "applicationCondition"))
        label, label_reason = _source_text(item.target, "target", row_ids, selected, "", required=has_target) \
            if has_target else (None, None)

        group_complex = _group_name(complex_name, item.complex_name, "complexName", row_ids, selected) or ""
        group_type = _group_name(housing_type, item.housing_type, "housingType", row_ids, selected)
        key = ("valid", complex_key(group_complex), complex_key(group_type)) if group_type else ("unresolved", index)
        unit = keys.get(key)
        if unit is None:
            unit = keys[key] = Unit(complex_name, housing_type, {})
            self.units.append(unit)
            self.rows[unit] = set()
            self.table_subtypes[unit] = []
            identities[unit] = []
        self._find(unit, "complexName", complex_reason)
        self._find(unit, "name", type_reason)
        entry = Entry(label, {**{name: _compact_number(item.values.get(name), name) for name in _TARGET_VALUE_FIELDS},
                              "applicationCondition": _compact_string(item.values.get("applicationCondition"))}) \
            if has_target else None
        self._find(entry, "label", label_reason)

        self.rows[unit].update(row_ids)
        table_subtypes = _table_subtypes(row_ids, housing_type, selected)
        self.table_subtypes[unit].extend(table_subtypes)
        for subtype in [] if table_subtypes else checks.objects(item.house_types):
            self._model_subtype(unit, subtype, row_ids, housing_type)
        for name in (*_ROW_FIELDS, "expectedMoveInMonth"):
            if item.values.get(name) is None:
                continue
            value = item.values[name]
            if name not in unit.values:
                unit.values[name] = value
            elif unit.values[name] != value:
                unit.values[name] = None
                self._find(unit, name, "같은 주택형 행에서 서로 다른 값이 나와 미확인으로 남김")
        # 읽었지만 하나로 정하지 못한 값은 그 단위·대상의 미확인으로 남긴다.
        for name, reason in item.unresolved:
            if name in _ROW_FIELDS:
                self._find(unit, name, reason)
        self.unit_of[item], self.entry_of[item] = unit, entry
        if entry is None:
            return
        # reserveCount는 이번 공고의 예비자 모집수라는 제품 계약의 핵심 값이다.
        if entry.values["reserveCount"] is None and label:
            self._find(entry, "reserveCount", _RESERVE_MISSING)
        for name, reason in item.unresolved:
            if name in entry.values:
                self._find(entry, name, reason)
        # 모델이 같은 표 행을 두 번 쓰면 값이 모두 같은 대상이 겹친다. 하나로 두고 원문 행만 합친다.
        # 원문에서 확인하지 못해 비운 대상명은 모델이 쓴 이름(가군·나군)이 다르면 다른 대상이다.
        identity = (_compact_string(item.target),
                    tuple(sorted((name, str(value)) for name, value in {"target": label, **entry.values}.items())))
        same = next((other for known, other in identities[unit] if known == identity), None)
        # 같은 표 행을 반복한 경우만 합친다. 원문 행이 다르면 이름·값이 같아도 다른 대상(다른 동·층)일 수 있다.
        if same is not None and self.rows[same] & set(row_ids):
            self.rows[same].update(row_ids)
            self.findings = [finding for finding in self.findings if finding.owner is not entry]
            self.entry_of[item] = same
            return
        unit.entries.append(entry)
        identities[unit].append((identity, entry))
        self.rows[entry] = set(row_ids)

    def _model_subtype(self, unit: Unit, subtype: dict, row_ids: list[str], housing_type: str | None) -> None:
        """모델이 낸 세부 주택형 하나를 원문 행에서 확인해 단위의 주택타입(house_types)으로 둔다."""
        selected = self.selected
        name = _compact_string(subtype.get("name"))
        if not name:
            return
        raw_text = "".join(selected.supply_row_text.get(row_id, "") for row_id in row_ids)
        supported = normalize_source_text(name) in normalize_source_text(raw_text)
        if not supported:
            self._find(unit, "houseTypes", f"세부 주택형 {name}을(를) 원문 표 행에서 확인하지 못함")
        clean = {"name": name, **{field: _compact_number(subtype.get(field), field) if supported else None
                                  for field in ("exclusiveArea", "commonArea", "constructedHouseholdCount")}}
        subtype_rows = [selected.supply_row_values.get(row_id, {}) for row_id in row_ids
                        if normalize_source_text(selected.supply_row_values.get(row_id, {}).get("housingType", ""))
                        == normalize_source_text(name)]
        labeled = _labeled_areas(row_ids, selected)
        label = _subtype_label(name, housing_type)
        if not subtype_rows and label in labeled:
            # 공급표가 세부 주택형 면적을 한 칸에 "(A)36.02 (B)36.19"로 적은 행이다. 그 칸의 표시로 면적을 확인한다.
            if clean["exclusiveArea"] is None:
                clean["exclusiveArea"] = labeled[label]
            subtype_rows = [{"exclusiveArea": str(labeled[label])}]
            # 모델이 세부 주택형을 A·B로만 적으면 행 형명의 숫자를 붙여 단지 주택형 이름(36A)으로 쓴다.
            if re.fullmatch(r"[A-Za-z]", name):
                base = re.match(r"\s*(\d+)", housing_type or "")
                if base:
                    name = clean["name"] = base.group(1) + name.upper()
        at = next((index for index, value in enumerate(unit.house_types) if value["name"] == name),
                  len(unit.house_types))
        for field in ("exclusiveArea", "commonArea", "constructedHouseholdCount"):
            value = clean[field]
            if value is not None and not any(numeric_cell_matches(value, source.get(field, ""), field)
                                             for source in subtype_rows):
                clean[field] = None
                self._find(unit, f"houseTypes[{at}].{field}", "세부 주택형의 같은 의미 원문 표 열에서 수치를 확인하지 못함")
        previous = next((value for value in unit.house_types if value["name"] == name), None)
        if previous is None:
            unit.house_types.append(clean)
        elif previous != clean:
            unit.house_types.remove(previous)
            self._find(unit, "houseTypes", f"세부 주택형 {name} 값이 서로 달라 미확인으로 남김")

    # ---------- 모집 칸을 함께 쓰는 주택형 ----------

    def _join_units_sharing_counts(self) -> None:
        """여러 주택형 행이 병합된 모집수 칸 하나를 함께 쓰면(탑석 센트럴 자이 49A·49B·49C의 모집 70) 공고가 그 주택형들을 한
        모집 단위로 묶은 것이다. 그 주택형들은 주택형 여럿(parts)인 모집 단위 하나이고 이름은 공통 형명(49)이다(사용자 결정:
        공고에서는 묶고 단지 주택타입은 뗀다). 공통 모집수가 단위를 넘나들면 실을 곳이 없어 모집수가 빠지기 때문이다.
        같은 단지, 대상 하나씩, 같은 대상 이름·임대조건이고 세부 타입이 없는 주택형만 묶는다. 주택형마다 단 미확인은 묶은
        단위와 그 대상으로 옮긴다. 이 시점에 조각 단위·대상을 가리키는 표는 findings·unit_of·entry_of뿐이다(confirmed·cites는
        묶은 뒤에 채운다). 묶기 전에 채우는 표를 늘리면 여기서도 옮겨야 한다."""
        units = self.units
        parent = list(range(len(units)))

        def root(index: int) -> int:
            while parent[index] != index:
                index = parent[index]
            return index
        for index, unit in enumerate(units):
            for row_id in self.rows[unit]:
                # 병합 칸이 없는 행 하나는 묶을 근거가 아니다. 위 행의 면적을 물려받으려고 근거 행에 넣은 행도 같은 행 하나다.
                if len(span := _merged_count_rows(row_id, self.selected)) == 1:
                    continue
                for other, other_unit in enumerate(units):
                    if other != index and span & self.rows[other_unit]:
                        parent[root(other)] = root(index)
        components: dict[int, list[Unit]] = {}
        for index, unit in enumerate(units):
            components.setdefault(root(index), []).append(unit)
        joinable = {at for at, parts in components.items() if len(parts) > 1 and self._can_join(parts)}
        if not joinable:
            return
        moved: dict[object, object] = {}
        result = []
        for index, unit in enumerate(units):
            if root(index) not in joinable:
                result.append(unit)
            elif unit is components[root(index)][0]:
                result.append(self._joined_unit(components[root(index)], moved))
        self.units = result
        self.findings = [replace(finding, owner=moved.get(finding.owner, finding.owner)) for finding in self.findings]
        self.unit_of = {item: moved.get(unit, unit) for item, unit in self.unit_of.items()}
        self.entry_of = {item: moved.get(entry, entry) for item, entry in self.entry_of.items()}

    def _can_join(self, parts: list[Unit]) -> bool:
        if len({complex_key(part.complex_name or "") for part in parts}) != 1:
            return False
        # 병합 칸이 덮는 주택형 행이 모두 묶을 주택형에 있어야 한다. 모델이 한 주택형(49A)을 빠뜨렸는데 묶으면
        # 그 주택형 몫까지 남은 주택형의 모집수가 된다.
        names = {normalize_source_text(part.name or "") for part in parts}
        covered = set().union(*(_merged_count_rows(row_id, self.selected) for part in parts for row_id in self.rows[part]))
        if any((name := normalize_source_text(self.selected.supply_row_values.get(row_id, {}).get("housingType", "")))
               and name not in names for row_id in covered):
            return False
        if any(len(part.entries) != 1 or part.house_types or self.table_subtypes[part] or not part.name for part in parts):
            return False
        return len({(normalize_source_text(part.entries[0].label or ""),
                     *(part.entries[0].values[name] for name in AMOUNT_FIELDS)) for part in parts}) == 1

    def _joined_unit(self, parts: list[Unit], moved: dict[object, object]) -> Unit:
        names = [part.name for part in parts]
        prefixes = {match.group(0) if (match := re.match(r"\d+", name)) else None for name in names}
        name = prefixes.pop() if len(prefixes) == 1 and None not in prefixes else "·".join(names)
        first = parts[0]
        values = {key: value for key, value in first.values.items()
                  if key not in _ROW_FIELDS and all(part.values.get(key) == value for part in parts)}
        source = first.entries[0]
        entry = Entry(source.label, {**source.values, "supplyHouseholdCount": None, "reserveCount": None})
        unit = Unit(first.complex_name, name, values, entries=[entry], parts=[
            HousingType(part.name, {**{field: self._part_value(part, field)
                                       for field in ("exclusiveArea", "commonArea", "constructedHouseholdCount")},
                                    "supplyArea": None}) for part in parts])
        self.rows[unit] = set().union(*(self.rows[part] for part in parts))
        self.rows[entry] = set().union(*(self.rows[part.entries[0]] for part in parts))
        self.table_subtypes[unit] = []
        # 공통 형명(49)은 원문 칸 하나에 없지만 묶은 주택형 칸들에서 코드가 만든 이름이라 원문 대조가 확인된 값으로 본다.
        self._confirm(unit, name=name)
        self.joined.add(unit)
        for part in parts:
            moved[part], moved[part.entries[0]] = unit, entry
        return unit

    def _part_value(self, part: Unit, field: str) -> float | None:
        """묶은 주택형 하나의 면적·건설호수. 그 주택형이 적힌 원문 행 칸의 값이고, 없으면 모델이 낸 값이다."""
        wanted = normalize_source_text(part.name)
        for row_id in sorted(self.rows[part]):
            values = self.selected.supply_row_values.get(row_id, {})
            if normalize_source_text(values.get("housingType", "")) != wanted:
                continue
            try:
                return float(str(values.get(field, "")).replace(",", "").strip())
            except ValueError:
                break
        return _compact_number(part.values.get(field), field)

    def _joined_counts(self, unit: Unit) -> None:
        """묶은 단위의 수량은 열마다 그 행들이 가진 칸(병합 칸은 한 번)의 합이다. 당첨자는 주택형 행마다, 예비자는 세 행에 병합된
        표(49A 5·49B 4·49C 3, 예비자 56)면 모집수는 12 + 56 = 68이다. 읽지 못한 칸이 있는 열은 합하지 않는다.
        모델이 낸 값은 바꾸지 않고, 합은 원문 칸 하나에 없어 원문 대조가 확인된 값으로 보게 둔다."""
        selected = self.selected
        row_ids = sorted(self.rows[unit])
        table = _table_of(row_ids[0], selected)
        if table is None or any(_table_of(row_id, selected) is not table for row_id in row_ids):
            return
        ids = [row.id for row in table.rows]
        sums: dict[str, str] = {}
        for field in _SOURCE_COUNT_FIELDS:
            if field not in table.columns:
                continue
            starts = {ids[_column_span(table, ids.index(row_id), table.columns[field])[0]] for row_id in row_ids}
            values = [count_or_dash(selected.supply_row_values.get(row_id, {}).get(field)) for row_id in starts]
            if starts <= self.rows[unit] and all(value is not None for value in values):
                sums[field] = str(sum(values))
        recruited, reserve = recruitment_count(sums), count_cell(sums.get("reserveCount"))
        if recruited is None:
            return
        unit.values.setdefault(TOTAL, recruited)
        entry = unit.entries[0]
        if entry.values["supplyHouseholdCount"] is None:
            entry.values["supplyHouseholdCount"] = recruited
        if entry.values["reserveCount"] is None and reserve is not None:
            entry.values["reserveCount"] = reserve
            self._drop(entry, "reserveCount")
        self.rows[entry].update(row_ids)
        self._confirm(unit, **{TOTAL: recruited})
        self._confirm(entry, supplyHouseholdCount=recruited, **({"reserveCount": reserve} if reserve is not None else {}))

    # ---------- 원문 칸에서 읽는 수량·면적 ----------

    def _counts_from_source_cells(self, unit: Unit) -> None:
        """모델이 모집수·예비자 수를 빠뜨린 단위는 그 단위가 가리키는 원문 표의 수량 행이 하나로 정해지면 그 칸을 쓴다.
        모집수는 입주자와 예비자를 합한 수다(사용자 결정). 모집호수 칸(입주자·예비자를 나눈 표의 '계')이 있으면 그 값,
        없으면 당첨자 칸과 예비자 칸의 합, 공급호수를 우선·일반 공급으로 나눈 표는 두 칸의 합이다. 예비자만 모집하는 표는 예비자 수다.
        다른 단위도 가리키는 수량 칸은 여러 단위의 공통 수량이라 채우지 않는다(나누거나 복제하지 않는다).
        대상이 여럿이면 수량 행 하나를 모든 대상의 공통 수량으로 두고(가군·나군), 대상마다 수량 행이 있으면 대상별로 읽는다
        (_target_counts). 모델이 낸 값은 뒤의 원문 대조에 맡기되, 공통 수량으로 둔 대상들의 값은 공통 수량이 대신해 비운다.
        주택타입이 여럿인 주택형을 한 단위로 뽑았으면 수량은 주택타입 행마다라 그 합이 단위 모집수다(_fill_block_counts)."""
        selected = self.selected
        house_type_unit = _house_type_unit(sorted(self.rows[unit]), selected, self.per_house_type_tables)
        if house_type_unit is not None and not house_type_unit.per_house_type and house_type_unit.rows[0].block_size > 1:
            self._fill_block_counts(unit, house_type_unit)
            return
        count_rows = [row_id for row_id in sorted(self.rows[unit])
                      if any(count_cell(selected.supply_row_values.get(row_id, {}).get(field)) is not None
                             for field in _SOURCE_COUNT_FIELDS)]
        if len(count_rows) > 1 and len(unit.entries) > 1:
            self._target_counts(unit, count_rows)
        if len(count_rows) != 1:
            return
        row_id = count_rows[0]
        span = _merged_count_rows(row_id, selected)
        if any(row_id in self.rows[other] for other in self.units if other is not unit) or not self._own_rows(span, unit):
            return
        cells = selected.supply_row_values[row_id]
        recruited, reserve = _recruited(row_id, selected), count_cell(cells.get("reserveCount"))
        if recruited is not None and recruitment_count(cells) is None:
            # 예비자만 모집하는 표의 모집수는 원문 칸 하나와 같지 않아 원문 대조가 이 값을 확인된 값으로 보게 한다.
            self._confirm(unit, **{TOTAL: recruited})
        if recruited is not None and TOTAL not in unit.values:
            unit.values[TOTAL] = recruited
        if len(unit.entries) != 1:
            if unit.entries:
                self._share_row_counts(unit, row_id, span, recruited, reserve)
            return
        entry = unit.entries[0]
        if entry.values["supplyHouseholdCount"] is None and recruited is not None:
            entry.values["supplyHouseholdCount"] = recruited
            if recruitment_count(cells) is None:
                self._confirm(entry, supplyHouseholdCount=recruited)
        if entry.values["reserveCount"] is None and reserve is not None:
            entry.values["reserveCount"] = reserve
            self.rows[entry].add(row_id)
            self._drop(entry, "reserveCount")
        if entry.values["supplyHouseholdCount"] is not None:
            self.rows[entry].add(row_id)

    def _own_rows(self, rows: set[str], unit: Unit) -> bool:
        """병합된 수량 칸이 덮는 행이 모두 이 단위의 행인지 본다. 어느 단위도 가리키지 않은 행은 주택형·대상 칸이 비어
        위 행에 이어진 줄(같은 주택형의 두 번째 면적 줄)일 때만 이 단위의 행이다."""
        for row_id in rows - self.rows[unit]:
            values = self.selected.supply_row_values.get(row_id, {})
            if any(row_id in self.rows[other] for other in self.units) or values.get("housingType") or values.get("target"):
                return False
        return True

    def _share_row_counts(self, unit: Unit, row_id: str, span: set[str], recruited: int | None,
                          reserve: int | None) -> None:
        """수량 행 하나를 여러 대상이 함께 쓰면(공급표는 주택형마다, 임대조건만 가군·나군으로 나눈 표) 그 수는 모든 대상의 공통 수량이다.
        대상마다 수량 표의 다른 행을 가리키면 대상별 수가 따로 있을 수 있어 공통으로 두지 않는다."""
        count_table_rows = {row.id for table in self.selected.supply_tables
                            if any(field in table.columns for field in _SOURCE_COUNT_FIELDS) for row in table.rows}
        if any((self.rows[entry] & count_table_rows) - span for entry in unit.entries):
            return
        for field, count in (("supplyHouseholdCount", recruited), ("reserveCount", reserve)):
            if count is None:
                continue
            self.code_shared.append((field, count, list(unit.entries), [row_id]))
            # 대상마다 같은 수를 복제한 값은 공통 수량이 대신한다.
            for entry in unit.entries:
                entry.values[field] = None

    def _target_counts(self, unit: Unit, count_rows: list[str]) -> None:
        """대상마다 수량 행이 있는 단위(인천석남 21A: 대학생·청년·주거급여 수급자 행)는 대상 이름이 적힌 행의 칸으로 대상별 모집수를 정한다.
        모집수 칸이 여러 대상 행에 걸쳐 병합되어 있으면(대학생·청년의 일반 공급 28) 그 칸을 나누지 않고, 그 칸을 함께 쓰는 대상들을
        한 공통 모집수로 둔다. 공통 모집수는 그 대상들의 자기 칸과 함께 쓰는 칸의 합이다(5 + 23 + 28 = 56, 사용자 결정).
        단위 모집수는 모든 칸의 합이다. 대상과 수량 행을 하나씩 맞추지 못하거나, 다른 단위 행이 섞이거나, 읽지 못한 칸이 있으면 정하지 않는다.
        모집호수(계) 열이나 우선·일반 공급 열이 있는 표만 본다. 당첨자·예비자로 나눈 표는 예비자 수를 대상별로 정해야 해 다루지 않는다."""
        selected = self.selected
        entries = unit.entries
        # 같은 이름의 대상 여럿(청년 소득(×)·소득(○) 금액 구간)은 공급표의 대상 행 하나를 함께 쓴다. 그 행의 수는 그 대상들의 공통 수량이다.
        owners: dict[str, set[int]] = {}
        for at, entry in enumerate(entries):
            name = normalize_source_text(entry.label or "")
            own = [row_id for row_id in count_rows if row_id in self.rows[entry] and name
                   and normalize_source_text(selected.supply_row_values[row_id].get("target", "")) == name]
            if len(own) != 1:
                return
            owners.setdefault(own[0], set()).add(at)
        for owned in owners.values():
            # 한 행을 함께 쓰는 대상들은 신청 조건(구간)으로만 갈린다. 조건이 없거나 같으면 어느 대상의 수인지 정할 수 없다.
            conditions = [entries[at].values.get("applicationCondition") for at in owned]
            if len(owned) > 1 and (None in conditions or len(set(conditions)) != len(conditions)):
                return
        table = _table_of(count_rows[0], selected)
        if set(owners) != set(count_rows) or table is None \
                or any(_table_of(row_id, selected) is not table for row_id in count_rows) \
                or any(set(count_rows) & self.rows[other] for other in self.units if other is not unit):
            return
        if TOTAL in table.columns:
            fields = (TOTAL,)
        elif "priorityCount" in table.columns and "generalCount" in table.columns:
            fields = ("priorityCount", "generalCount")
        else:
            return
        ids = [row.id for row in table.rows]
        own_counts = dict.fromkeys(range(len(entries)), 0)
        spans: dict[tuple[str, int], tuple[int, set[int], list[str]]] = {}
        for row_id, owned in owners.items():
            at = ids.index(row_id)
            for field in fields:
                column = table.columns[field]
                start, end = _column_span(table, at, column)
                value = count_or_dash(table.cells[start][column] if column < len(table.cells[start]) else None)
                members = set().union(*(owners.get(ids[position], {None}) for position in range(start, end + 1)))
                if value is None or None in members:
                    return
                if start == end and len(owned) == 1:
                    own_counts[next(iter(owned))] += value
                else:
                    spans[(field, start)] = (value, members, ids[start:end + 1])
        components = [{at} for at in range(len(entries))]
        for _, members, _ in spans.values():
            joined = [component for component in components if component & members]
            components = [component for component in components if not component & members] + [set().union(*joined)]
        for component in components:
            shared = [(value, rows) for value, members, rows in spans.values() if members & component]
            count = sum(own_counts[at] for at in component) + sum(value for value, _ in shared)
            if len(component) == 1:
                entry = entries[next(iter(component))]
                if entry.values["supplyHouseholdCount"] is None:
                    entry.values["supplyHouseholdCount"] = count
                self._confirm(entry, supplyHouseholdCount=count)
                continue
            rows = sorted({row_id for row_id, owned in owners.items() if owned & component}
                          | {row_id for _, span_rows in shared for row_id in span_rows}, key=ids.index)
            self.code_shared.append(("supplyHouseholdCount", count, [entries[at] for at in sorted(component)], rows))
            for at in component:
                entries[at].values["supplyHouseholdCount"] = None
        total = sum(own_counts.values()) + sum(value for value, _, _ in spans.values())
        self._confirm(unit, **{TOTAL: total})
        unit.values.setdefault(TOTAL, total)

    def _fill_block_counts(self, unit: Unit, house_type_unit: "_HouseTypeUnit") -> None:
        """주택타입 여럿을 묶은 주택형 단위(84 = 84A·84B…)의 모집수는 주택타입 행마다 따로 적힌 모집수의 합이다(사용자 결정).
        예비자 수도 같은 행들의 합이다. 수량 칸이 그 주택형의 주택타입 행 전체에 걸쳐 하나로 병합되면(울산다운2 55 = 55A·55B·55C의
        당첨자 103·예비자 100) 그 칸이 주택형의 모집수다. 단지 주택타입은 세부 타입마다 따로 두고 이 합은 공고 단위 한 줄에만 둔다.
        그 밖에 병합된 공통 수량 칸이 섞이거나, 한 타입이라도 수가 없거나, 다른 단위도 그 행의 수량을 가리키면 정하지 않는다.
        합은 block_counts에 두어 원문 대조가 그 값을 확인된 값으로 본다. 모델이 낸 값은 바꾸지 않는다.
        합을 정하지 못한 필드는 None으로 두어, 모델이 낸 한 타입의 수(84A 41)도 원문 대조에서 비운다.
        근거 행은 넓히지 않는다. 다른 주택타입 행의 면적·금액 칸으로 다른 값이 통과하지 않게 수량만 이 합과 대조한다."""
        selected = self.selected
        block_counts = self.block_counts[unit] = dict.fromkeys(_COUNT_FIELDS)
        start = house_type_unit.rows[0]
        block = sorted((row_id for row_id in selected.supply_row_values
                        if (row := _house_type_row(row_id, selected)) is not None and row.start == start.start),
                       key=lambda row_id: int(row_id.rsplit(":R", 1)[1]))
        if len(block) != start.block_size or any(set(block) & self._count_rows(self.rows[other], other)
                                                 for other in self.units if other is not unit):
            return
        if all(_merged_count_rows(row_id, selected) == {row_id} for row_id in block):
            rows = block
        elif _counts_merged_over(block, selected):
            rows = block[:1]
        else:
            return
        recruited = [recruitment_count(selected.supply_row_values[row_id]) for row_id in rows]
        reserves = [count_cell(selected.supply_row_values[row_id].get("reserveCount")) for row_id in rows]
        if any(count is None for count in recruited):
            return
        counts = {TOTAL: sum(recruited), "supplyHouseholdCount": sum(recruited)}
        if all(count is not None for count in reserves):
            counts["reserveCount"] = sum(reserves)
        block_counts.update(counts)
        if TOTAL not in unit.values:
            unit.values[TOTAL] = counts[TOTAL]
        if len(unit.entries) != 1:
            return
        entry = unit.entries[0]
        if entry.values["supplyHouseholdCount"] is None:
            entry.values["supplyHouseholdCount"] = counts["supplyHouseholdCount"]
        if entry.values["reserveCount"] is None and "reserveCount" in counts:
            entry.values["reserveCount"] = counts["reserveCount"]
            self._drop(entry, "reserveCount")

    def _count_rows(self, rows: set[str], unit: Unit) -> set[str]:
        """단위·대상이 가리키는 행 가운데 그 수량을 읽을 수 있는 행. 주택형 칸에 다른 단위의 주택형이 적힌 행은 병합된 공급대상·단지
        칸의 근거로만 가리킨 행이라(울산다운2 55(주거약자용)이 55A 행의 공급대상 칸을 함께 씀) 그 행의 수량은 이 단위 것이 아니다.
        이름이 원문 칸과 조금 달라도(21A형) 다른 단위 이름과 같다고 확인되지 않은 행은 남겨 병합 수량 검사를 건너뛰지 않는다."""
        own = normalize_source_text(unit.name or "")
        others = {normalize_source_text(other.name or "") for other in self.units if other is not unit} - {own, ""}
        values = self.selected.supply_row_values
        return {row_id for row_id in rows
                if normalize_source_text(values.get(row_id, {}).get("housingType", "")) not in others}

    def _leave_merged_cells_unconfirmed(self, unit: Unit) -> None:
        """대상의 수량 칸이 그 대상 밖의 행까지 병합된 칸이면(대학생·청년이 함께 쓰는 일반 공급 28) 여러 대상의 공통 수량이다.
        병합이 시작된 행에는 그 칸 값이 그대로 있어, 코드가 대상 수를 정하지 못했을 때 모델이 낸 5 + 28이 대학생 몫으로
        확인되지 않게 그 대상의 수량은 어떤 값도 확인된 값으로 보지 않는다."""
        for entry in unit.entries:
            confirmed = dict(self.confirmed.get(entry, {}))
            for row_id in sorted(self._count_rows(self.rows[entry], unit)):
                table = _table_of(row_id, self.selected)
                if table is None:
                    continue
                ids = [row.id for row in table.rows]
                for field in _SOURCE_COUNT_FIELDS:
                    if field not in table.columns:
                        continue
                    start, end = _column_span(table, ids.index(row_id), table.columns[field])
                    if set(ids[start:end + 1]) <= self.rows[entry]:
                        continue
                    self._confirm(entry, **{name: None for name in ("supplyHouseholdCount", "reserveCount")
                                            if name not in confirmed})

    def _area_from_source_cells(self, unit: Unit) -> None:
        """모델이 전용면적을 빠뜨린 단위는 그 단위가 가리키는 원문 표 칸의 전용면적이 하나로 정해지면 그 값을 쓴다.
        단위를 (단지, 전용면적)으로 기존 공급행·주택형에 잇기 때문에 면적이 비면 관리자가 직접 맞춰야 한다.
        세부 주택형을 둔 단위와, 같은 행에서 값이 서로 달라 비운 면적(None)은 채우지 않는다."""
        if unit.house_types or unit.parts or "exclusiveArea" in unit.values:
            return
        areas = set()
        wanted = normalize_source_text(unit.name or "")
        for row_id in self.rows[unit]:
            values = self.selected.supply_row_values.get(row_id, {})
            # 단지명만 이어받으려고 가리킨 위 행처럼 다른 주택형이 적힌 행의 면적은 이 단위의 면적이 아니다.
            # 주택타입마다 뽑은 단위(84A)는 그 행의 주택타입 칸이 표기와 같다.
            if wanted and values.get("housingType") and normalize_source_text(values["housingType"]) != wanted \
                    and normalize_source_text(values.get("houseType", "")) != wanted:
                continue
            try:
                areas.add(float(str(values.get("exclusiveArea", "")).replace(",", "").strip()))
            except ValueError:
                continue
        if not areas and unit.name:
            # 모델이 임대조건표 행만 가리키면 그 행에는 면적 칸이 없다. 공급표에서 형명이 같은 행의 면적이 하나면 그 값이다.
            # 그 행 ID를 단위 원문 행에 더해야 뒤의 원문 대조가 면적을 지우지 않는다.
            found = {}
            complex_name = normalize_source_text(unit.complex_name or "")
            for row_id, values in self.selected.supply_row_values.items():
                # 다단지 공고에서 다른 단지의 같은 형명 행 면적을 가져오지 않는다. 단지명 칸이 있는 행은 같은 단지만 본다.
                if complex_name and values.get("complexName") \
                        and normalize_source_text(values["complexName"]) != complex_name:
                    continue
                if normalize_source_text(values.get("housingType", "")) == wanted:
                    try:
                        found[row_id] = float(str(values.get("exclusiveArea", "")).replace(",", "").strip())
                    except ValueError:
                        continue
            if len(set(found.values())) == 1:
                self.rows[unit].update(found)
                areas = set(found.values())
        if len(areas) == 1:
            unit.values["exclusiveArea"] = areas.pop()

    # ---------- 값 정리·근거 ----------

    def _finish(self, unit: Unit) -> None:
        """단위 값을 계약의 수로 정리하고(면적은 소수, 수량은 정수), 단위·대상마다 원문 행 근거를 단다."""
        unit.values = {**{name: _compact_number(unit.values.get(name), name) for name in _ROW_FIELDS},
                       "expectedMoveInMonth": unit.values.get("expectedMoveInMonth")}
        subtypes = _subtype_values(self.table_subtypes[unit]) or unit.house_types
        unit.house_types = [{"name": subtype.get("name"), **{name: subtype.get(name) for name in AREA_FIELDS}}
                            for subtype in subtypes]
        row_ids = sorted(self.rows[unit])
        self.cites += self._cites(unit, "", row_ids)
        self.cites += self._value_cites(unit, None, _ROW_FIELDS, row_ids)
        for entry in unit.entries:
            row_ids = sorted(self.rows[entry])
            self.cites += self._cites(entry, "", row_ids)
            self.cites += self._value_cites(entry, entry.label, _TARGET_VALUE_FIELDS, row_ids)

    def _cites(self, owner: object, name: str, row_ids: list[str]) -> list[Cite]:
        return [Cite(owner, name, page, quote) for page, quote in _quotes(row_ids, self.selected)]

    def _value_cites(self, owner: Unit | Entry, label: object, fields: tuple[str, ...], row_ids: list[str]) -> list[Cite]:
        """단위·대상 근거는 여러 쪽(공급표·임대조건표)에 걸칠 수 있다. 화면은 같은 경로의 첫 근거를 쓰므로,
        값마다 그 칸이 적힌 행만 가리키는 근거를 따로 둔다(보증금 근거가 공급표 쪽을 가리키지 않게)."""
        if len({row_id.split(":")[0] for row_id in row_ids}) <= 1:
            return []  # 근거가 한 쪽뿐이면 단위·대상 근거가 이미 그 쪽을 가리킨다. 근거 개수 상한(화면 5000개)을 아낀다.
        selected = self.selected
        result = []
        for name in fields:
            value = owner.values.get(name)
            if value is None:
                continue
            cells = {row_id: target_cells(label, row_id, selected.supply_row_values, selected.supply_row_target_values)
                     for row_id in row_ids}
            ids = [row_id for row_id, cell in cells.items() if cell.get(name)]
            # 이름을 이어받으려고 넣은 다른 줄(예: 소득 없음 줄)도 같은 열에 값이 있어, 값이 실제로 맞는 줄을 먼저 쓴다.
            matching = [row_id for row_id in row_ids if source_cell_matches(value, cells[row_id], name)]
            result += self._cites(owner, name, matching or ids)[:1]
        return result

    def _complexes(self) -> dict:
        """단위의 단지명마다 단지 하나. 단지 값(소재지·세대수·주차)은 그 단지 단위들의 원문 행 칸이 하나로 정해질 때만 둔다."""
        names: list[str] = []
        for unit in self.units:
            add_complex_name(names, unit.complex_name)
        complexes, unresolved, evidence = [], [], []
        for name in names:
            row_ids = set()
            values = {"totalHouseholdCount": set(), "parkingCount": set()}
            addresses: dict[str, str] = {}
            for unit in self.units:
                if complex_key(unit.complex_name) != complex_key(name):
                    continue
                row_ids.update(self.rows[unit])
                for row_id in self.rows[unit]:
                    cells = self.selected.supply_row_values.get(row_id, {})
                    for field in values:
                        parsed = _integer_cell(cells.get(field))
                        if parsed is not None:
                            values[field].add(parsed)
                    address = normalize_address(cells.get("address"))
                    if address:
                        addresses.setdefault(address_key(address), address)
            path = f"complexes[{len(complexes)}]"
            record = {"sourceComplexName": name, "address": next(iter(addresses.values())) if len(addresses) == 1 else None}
            if len(addresses) > 1:
                unresolved.append({"path": f"{path}.address", "reason": "공급 표의 같은 단지 소재지가 서로 달라 미확인으로 남김"})
            for field, candidates in values.items():
                record[field] = next(iter(candidates)) if len(candidates) == 1 else None
                if len(candidates) > 1:
                    unresolved.append({"path": f"{path}.{field}", "reason": "공급 표의 같은 단지 값이 서로 달라 미확인으로 남김"})
            complexes.append(record)
            evidence += [{"path": path, "page": page, "quote": quote}
                         for page, quote in _quotes(sorted(row_ids), self.selected)]
        return {"data": {"complexes": complexes, "unresolvedFields": deduplicate(unresolved)},
                "notApplicable": [], "evidence": evidence}

    # ---------- 함께 쓰는 수량 ----------

    def _shared_counts(self) -> None:
        """여러 칸이 함께 쓰는 수량 칸을 원문과 대조해 싣는다. 초안의 공통 수량(병합 칸) 다음에 코드가 원문 칸에서 정한 공통 수량이다."""
        selected = self.selected
        code_owners = {(entry, field) for field, _, entries, _ in self.code_shared for entry in entries}
        for shared in self.draft.shared:
            field = shared.field
            count = _compact_number(shared.count, field)
            row_ids = list(dict.fromkeys(shared.row_ids))
            owners, units = [], []
            for cell in shared.cells:
                owner = self.unit_of[cell] if field == TOTAL else self.entry_of[cell]
                if owner is not None and all(owner is not known for known in owners):
                    owners.append(owner)
                    units.append(self.unit_of[cell])
            if len(owners) < 2 and all(unit in self.joined for unit in units):
                continue  # 모집 칸을 함께 쓰는 주택형들로 묶은 단위의 공통 수량이다. 묶은 단위의 수량은 원문 칸에서 읽는다.
            if any((owner, field) in code_owners for owner in owners):
                continue  # 같은 대상의 공통 수량은 원문 칸에서 읽은 값(아래)을 쓴다.
            supported = count is not None and any(
                source_cell_matches(count, selected.supply_row_values.get(row_id, {}), field) for row_id in row_ids)
            if len(owners) < 2 or not supported:
                for owner in owners:
                    self._find(owner, field, "공통 수량을 같은 의미의 원문 표 열에서 확인하지 못함")
                continue
            page_match = next((match for row_id in row_ids if (match := re.match(r"P(\d+):T\d+:R\d+", row_id))), None)
            page = int(page_match.group(1)) if page_match else None
            quote_ids = [row_id for row_id in row_ids if re.match(rf"P{page}:T\d+:R\d+", row_id)] if page else []
            quote = " ".join(selected.supply_row_text.get(row_id, "").strip() for row_id in quote_ids)[:500]
            if page is None or not quote:
                for owner in owners:
                    self._find(owner, field, "공통 수량의 원문 쪽과 인용을 만들지 못함")
                continue
            self._add_shared(field, count, owners, page, quote)
        for field, count, entries, row_ids in self.code_shared:
            page = int(re.match(r"P(\d+):", row_ids[0]).group(1))
            quote = " ".join(selected.supply_row_text.get(row_id, "").strip() for row_id in row_ids
                             if row_id.startswith(f"P{page}:"))[:500]
            self._add_shared(field, count, list(entries), page, quote)

    def _add_shared(self, field: str, count: int, owners: list, page: int, quote: str) -> None:
        """공통 수량 하나를 싣고, 공통 수량이 대신하는 '예비자 모집수 미확인'을 지운다."""
        self.shared.append(Shared(COUNT_KINDS[field], count, owners, page, quote))
        for owner in owners:
            self._drop(owner, field, _RESERVE_MISSING)

    # ---------- 원문 대조 ----------

    def _check_source(self) -> list[tuple[Unit | Entry, str]]:
        """단위·대상마다 근거와 값을 원문과 대조한다(source_check). 근거를 확인하지 못한 단위·대상과 그 사유를 돌려준다."""
        failures = []
        for unit in self.units:
            for owner in (unit, *unit.entries):
                cite = next((cite for cite in self.cites if cite.owner is owner and not cite.field), None)
                confirmed = {**self._block_confirmed(unit, owner), **self.confirmed.get(owner, {})}
                findings, failure = check_source(owner, self.rows[owner], cite, self.selected, self.headings, confirmed)
                self.findings += findings
                if failure is not None:
                    failures.append((owner, failure))
        return failures

    def _block_confirmed(self, unit: Unit, owner: Unit | Entry) -> dict:
        """주택타입 행마다 적힌 모집수의 합은 원문 칸 하나에는 없지만 그 행들에서 확인한 값이다. 대상이 여럿이면 주택타입 행 합을
        대상별로 나눌 근거가 없어, 한 타입 칸(41)과 맞아도 대상 수량으로 확인하지 않는다."""
        counts = self.block_counts.get(unit, {})
        if owner is unit:
            return {name: value for name, value in counts.items() if name == TOTAL}
        if len(unit.entries) == 1:
            return {name: value for name, value in counts.items() if name != TOTAL}
        return {"supplyHouseholdCount": None, "reserveCount": None} if counts else {}

    def _all_applicants_when_no_target_column(self, unit: Unit) -> None:
        """공급표·임대조건표에 공급대상 열이 없는 행은 신청자 구분이 없는 행이다. 대상이 하나면 모델이 지어낸 이름(일반) 대신
        LH 정제가 같은 행에 쓰는 '전체'로 두고 미확인으로 남기지 않는다. 대상 열이 있는데 확인하지 못한 이름은 그대로 비워 둔다.
        원문 대조가 끝난 뒤에 정한다. '전체'는 원문 칸에 없는 값이라 대조하면 다시 지워지기 때문이다."""
        if len(unit.entries) != 1 or unit.entries[0].label:
            return
        entry = unit.entries[0]
        row_ids = self.rows[unit] | self.rows[entry]
        values = self.selected.supply_row_values
        # 입력 표에 없는 행 ID만 가진 항목은 표 구조를 모르므로 대상 열이 없다고 볼 수 없다. 금액 열을 신청자 묶음(가군·나군)으로
        # 나눈 표도 대상이 있는 표다.
        if not row_ids or any(row_id not in values for row_id in row_ids) \
                or any("target" in values[row_id] or self.selected.supply_row_target_values.get(row_id)
                       for row_id in row_ids):
            return
        entry.label = "전체"
        self._drop(entry, "label")

    def _tell_apart_same_names(self) -> None:
        """이름이 같은 단위(단지명을 확인하지 못한 두 단지의 26.37)는 경고에서 가릴 수 있게 첫 원문 행의 자리를 이름에 붙인다.
        면적을 물려준 위 행처럼 다른 단위도 가진 행은 그 단위만의 행이 있으면 쓰지 않는다."""
        same_names: dict[str, list[Unit]] = {}
        for unit in self.units:
            same_names.setdefault(unit.label(), []).append(unit)
        for units in same_names.values():
            if len(units) < 2:
                continue
            for unit in units:
                others = set().union(*(self.rows[other] for other in units if other is not unit))
                row_ids = (self.rows[unit] - others) or self.rows[unit]
                places = sorted(tuple(int(number) for number in match.groups()) for row_id in row_ids
                                if (match := re.fullmatch(r"P(\d+):T(\d+):R(\d+)", row_id)))
                if places:
                    page, table, row = places[0]
                    unit.place = f"원본 {page}쪽 표 {table}의 {row}행"


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
    """단위 하나가 가리키는 주택형 칸 안의 주택타입 행들과, 그 행 단위에 맞는 표기(label)."""

    rows: tuple[_HouseTypeRow, ...]
    per_house_type: bool

    def label(self, candidate: object) -> tuple[str | None, str | None]:
        """주택타입마다 뽑은 단위는 주택타입, 주택형마다 뽑은 단위는 주택형 표기다(사용자 결정). 모델이 다른 열(주택형·주택타입)의
        값을 썼어도 같은 행의 원문 칸이면 행 단위에 맞는 열의 값으로 바꾼다. 어느 칸에도 없는 값은 미확인이다(표기, 사유)."""
        wanted = self.rows[0].house_type if self.per_house_type else self.rows[0].housing_type
        accepted = {normalize_source_text(value) for row in self.rows for value in (row.housing_type, row.house_type)}
        if isinstance(candidate, str) and candidate.strip() and normalize_source_text(candidate) not in accepted:
            return None, "추출한 값이 같은 의미의 원문 표 열에서 확인되지 않음"
        return wanted, None


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


def _per_house_type_tables(items: list[DraftCell], selected: pages.PromptPages) -> set[str]:
    """주택타입 행 하나만 분명히 가리키는 공급행이 있는 표. 그 표의 공급행은 주택타입마다 뽑은 것이다."""
    tables_ = set()
    for item in items:
        unit = _house_type_unit(list(dict.fromkeys(item.row_ids)), selected, set())
        if unit is not None and unit.per_house_type:
            tables_.add(unit.rows[0].table)
    return tables_



def _recruited(row_id: str, selected: pages.PromptPages) -> int | None:
    """원문 행의 모집수(recruitment_count). 입주자 열 없이 '금회 모집 예비자'만 적은 표(예비입주자 모집 공고)는 입주자가 0이라
    예비자 수가 모집수다. 모집 열을 읽지 못한 표(공급호수 등)에서 예비자 수만 모집수로 잡지 않게, 표에 다른 수량 열이 없고
    예비자 열 머리글에 '모집'이 있을 때만 그렇게 본다."""
    cells = selected.supply_row_values.get(row_id, {})
    recruited = recruitment_count(cells)
    if recruited is not None:
        return recruited
    table = _table_of(row_id, selected)
    if table is None or "reserveCount" not in table.columns \
            or any(field in table.columns for field in _SOURCE_COUNT_FIELDS if field != "reserveCount"):
        return None
    headers = tables.table_column_headers(table.cells[:6], max(len(row) for row in table.cells))
    if "모집" not in headers[table.columns["reserveCount"]]:
        return None
    return count_cell(cells.get("reserveCount"))



def _table_of(row_id: str, selected: pages.PromptPages) -> "tables.SupplyTable | None":
    return next((table for table in selected.supply_tables if any(row.id == row_id for row in table.rows)), None)


def _column_span(table: "tables.SupplyTable", index: int, column: int) -> tuple[int, int]:
    """그 행의 칸과 세로 병합(None)으로 이어진 행 범위(처음, 끝)."""
    def merged(at: int) -> bool:
        return column < len(table.cells[at]) and table.cells[at][column] is None
    start = index
    while start > 0 and merged(start):
        start -= 1
    end = index
    while end + 1 < len(table.cells) and merged(end + 1):
        end += 1
    return start, end



_COUNT_FIELDS = ("totalSupplyHouseholdCount", "supplyHouseholdCount", "reserveCount")
# 모집수를 읽는 원문 칸. 공급호수를 우선·일반 공급으로 나눈 표는 두 칸의 합이 모집수다.
_SOURCE_COUNT_FIELDS = (*_COUNT_FIELDS, "priorityCount", "generalCount")


def _counts_merged_over(block: list[str], selected: pages.PromptPages) -> bool:
    """표의 수량 열마다 칸 하나가 block 행 전체에 걸쳐 세로로 병합됐는가. 병합이 block 밖으로 이어지거나
    일부 열만 행마다 따로 적혔으면 아니다."""
    for table in selected.supply_tables:
        ids = [row.id for row in table.rows]
        if block[0] not in ids:
            continue
        first = ids.index(block[0])
        last = first + len(block) - 1
        columns = [table.columns[field] for field in _SOURCE_COUNT_FIELDS if field in table.columns]
        return ids[first:last + 1] == block and bool(columns) \
            and all(_column_span(table, first, column) == (first, last) for column in columns)
    return False


def _merged_count_rows(row_id: str, selected: pages.PromptPages) -> set[str]:
    """그 행의 수량 칸과 세로 병합(None)으로 이어진 행들이다. 수량 열 하나라도 위 행에서 내려오거나 아래 행으로 이어지면
    그 칸은 여러 행이 함께 쓰는 수량이다. 병합이 없으면 그 행 하나다."""
    rows = {row_id}
    for table in selected.supply_tables:
        ids = [row.id for row in table.rows]
        if row_id not in ids:
            continue
        index = ids.index(row_id)
        for column in (table.columns[field] for field in _SOURCE_COUNT_FIELDS if field in table.columns):
            start, end = _column_span(table, index, column)
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



def _source_text(candidate: object, field: str, row_ids: list[str], selected: pages.PromptPages,
                 missing_value: str | None, required: bool = True) -> tuple[str | None, str | None]:
    """칸 초안의 표기를 같은 의미의 원문 칸에서 확인한 값(표기, 확인하지 못한 사유)."""
    source_values = source_texts(field, row_ids, selected.supply_row_values, selected.supply_row_target_values)
    if isinstance(candidate, str) and candidate.strip():
        matched = next((value for value in source_values
                        if normalize_source_text(value) == normalize_source_text(candidate)), None)
        if matched is not None:
            return _normalize_source_display(matched, field), None
        return missing_value, "추출한 값이 같은 의미의 원문 표 열에서 확인되지 않음"
    if len(source_values) == 1:
        return _normalize_source_display(source_values[0], field), None
    if required:
        return missing_value, "원문 표 행 ID가 없거나 해당 열의 값을 확인하지 못함"
    if len(source_values) > 1:
        return missing_value, "여러 원문 표 값이 있어 하나로 결정하지 못함"
    return missing_value, None


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



def _integer_cell(value: object) -> int | None:
    if not isinstance(value, str):
        return None
    number = re.sub(r"[^\d-]", "", value.replace(",", ""))
    try:
        return int(number) if number and number != "-" else None
    except ValueError:
        return None



def _quotes(row_ids: list[str], selected: pages.PromptPages) -> list[tuple[int, str]]:
    """원문 행들의 쪽마다 (쪽, 인용)."""
    grouped: dict[int, list[str]] = {}
    for row_id in row_ids:
        match = re.match(r"P(\d+):T\d+:R\d+", row_id)
        if not match:
            continue
        source_page = int(match.group(1))
        if source_page not in selected.supply_pages:
            continue
        grouped.setdefault(source_page, []).append(row_id)
    return [(page, " ".join(selected.supply_row_text[row_id].strip() for row_id in ids
                            if selected.supply_row_text.get(row_id))[:500])
            for page, ids in grouped.items()]


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
