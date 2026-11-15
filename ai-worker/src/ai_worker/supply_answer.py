"""공급 단계 모델의 공급표 모양 답(주택형·공급대상·수량 칸·임대조건 칸)을 조립(supply_assembly)이 읽는 짧은 공급행 목록으로 옮긴다.

모델은 공고문 표의 칸 단위로 답한다. 병합된 수량 칸 하나는 멤버가 여럿인 quotas 하나이고, 금액 칸 한 세트는 leaseTerms 하나다.
그래서 같은 수를 대상마다 복제하거나 공통 수량을 행 번호로 따로 적지 않는다. 조립과 원문 대조 검산은 코드가 표에서 묶은 행과
같은 짧은 행 목록을 읽으므로, 여기서 칸(주택형×공급대상)마다 행 하나를 만들고 칸 하나만 가리키는 수는 그 행에, 여러 칸이 함께 쓰는
수는 공통 수량(sharedCounts)으로 둔다. 같은 칸의 수량 열(우선·일반공급)을 더하는 것 말고는 값을 옮길 뿐 새로 계산하지 않는다.
"""

from ai_worker import checks
from ai_worker.supply_assembly import Draft, DraftCell, SharedCount

_TYPE_FIELDS = ("exclusiveArea", "commonArea", "supplyArea", "constructedHouseholdCount", "expectedMoveInMonth")
_AMOUNT_FIELDS = ("rentalDeposit", "monthlyRent", "convertedDeposit")
_CELL_FIELDS = {"WINNER": "supplyHouseholdCount", "RESERVE": "reserveCount"}


def draft(answer: dict) -> tuple[Draft, list[str]]:
    """공급표 모양 답을 칸 초안으로 옮기고, 옮기지 못한 칸의 경고를 함께 돌려준다."""
    return _Draft(answer.get("data") or {}).build()


class _Draft:
    def __init__(self, data: dict):
        self.types = [value for value in checks.objects(data.get("housingTypes")) if _key(value)]
        self.targets = {_key(value): value for value in checks.objects(data.get("targets")) if _key(value)}
        self.quotas = checks.objects(data.get("quotas"))
        self.leases = checks.objects(data.get("leaseTerms"))
        self.type_keys = {_key(value) for value in self.types}
        self.items: list[DraftCell] = []
        self.cell_at: dict[tuple[str, str | None], int] = {}
        self.warnings: list[str] = [
            f"공급 단계 응답 housingTypes[{at}]에 key가 없어 그 주택형을 뺐습니다."
            for at, value in enumerate(checks.objects(data.get("housingTypes"))) if not _key(value)]

    def build(self) -> tuple[Draft, list[str]]:
        members = [self._members(at, "quotas", quota) for at, quota in enumerate(self.quotas)]
        lease_members = [self._members(at, "leaseTerms", lease) for at, lease in enumerate(self.leases)]
        self._cells([cell for cells in (*members, *lease_members) for cell in cells if cell[1] is not None])
        # 대상 구분 없이 주택형으로만 가리킨 수량은 행 모집수다(조립이 대상 수로 읽지 않는다).
        by_type = [bool(cells) and all(target is None for _, target in cells) for cells in members]
        members = [self._expand(cells) for cells in members]
        lease_members = [self._expand(cells) for cells in lease_members]
        for lease, cells in zip(self.leases, lease_members):
            self._lease(lease, cells)
        shared = []
        for at, quota, cells, whole in _summed(self.quotas, members, by_type):
            shared += self._quota(at, quota, cells, whole)
        return Draft(self.items, shared), self.warnings

    # ---------- 칸 ----------

    def _members(self, at: int, name: str, value: dict) -> list[tuple[str, str | None]]:
        """[주택형 key, 대상 key]는 칸 하나, [주택형 key]는 그 주택형의 모든 칸이다(대상 구분 없이 주택형 줄에 적힌 값)."""
        cells = []
        members = value.get("members")
        if not isinstance(members, list):
            self.warnings.append(f"공급 단계 응답 {name}[{at}]의 members가 목록이 아니라 그 값을 뺐습니다.")
            members = []
        for member in members:
            pair = (*member, None)[:2] if isinstance(member, list) and len(member) in (1, 2) \
                and all(isinstance(key, str) for key in member) else None
            if pair and pair[0] in self.type_keys and (pair[1] is None or pair[1] in self.targets):
                cells.append(pair)
            else:
                self.warnings.append(f"공급 단계 응답 {name}[{at}]의 멤버 {member}가 가리키는 주택형·공급대상이 없어 그 멤버를 뺐습니다.")
        return cells

    def _expand(self, cells: list[tuple[str, str | None]]) -> list[tuple[str, str | None]]:
        expanded = [cell for owner, target in cells
                    for cell in (sorted(self._type_cells(owner), key=self.cell_at.get) if target is None else [(owner, target)])]
        return list(dict.fromkeys(expanded))

    def _cells(self, member_cells: list[tuple[str, str]]) -> None:
        """주택형마다 그 주택형이 적은 공급대상 순서로 칸 행을 만들고, 수량·임대조건 멤버로만 나온 칸을 뒤에 더한다.
        공급대상이 하나도 없는 주택형은 대상 없는 행 하나다(조립이 대상 열 없는 표로 다룬다)."""
        for housing_type in self.types:
            key = _key(housing_type)
            targets = housing_type.get("targets")
            listed = [target for target in targets if isinstance(target, str) and target in self.targets] \
                if isinstance(targets, list) else []
            extra = [target for owner, target in member_cells if owner == key and target not in listed]
            targets = list(dict.fromkeys([*listed, *extra]))
            for target in targets or [None]:
                self.cell_at[(key, target)] = len(self.items)
                self.items.append(self._item(housing_type, target))

    def _item(self, housing_type: dict, target: str | None) -> DraftCell:
        item = DraftCell(_row_ids(housing_type), housing_type.get("complexName"), housing_type.get("name"),
                         house_types=checks.objects(housing_type.get("houseTypes")))
        item.values.update({field: housing_type[field] for field in _TYPE_FIELDS if housing_type.get(field) is not None})
        if target is not None:
            value = self.targets[target]
            item.target, item.targeted = value.get("label"), True
            if value.get("applicationCondition") is not None:
                item.values["applicationCondition"] = value["applicationCondition"]
        return item

    def _type_cells(self, key: str) -> set[tuple[str, str | None]]:
        return {cell for cell in self.cell_at if cell[0] == key}

    def _set(self, cell: tuple[str, str | None], field: str, value: object, row_ids: list[str]) -> None:
        """칸 행 하나에 값을 싣는다. 두 수량·임대조건이 같은 칸에 다른 값을 주면 그 값은 미확인이다."""
        item = self.items[self.cell_at[cell]]
        item.row_ids = list(dict.fromkeys([*item.row_ids, *row_ids]))
        if field in item.values and item.values[field] != value:
            item.values[field] = None
            item.unresolved.append((field, "공급 단계 응답이 같은 칸에 서로 다른 값을 줘 미확인으로 남김"))
        elif field not in item.values:
            item.values[field] = value

    # ---------- 임대조건·수량 ----------

    def _lease(self, lease: dict, cells: list[tuple[str, str | None]]) -> None:
        """금액 칸 한 세트는 그 칸이 가리키는 칸 행마다 같은 금액이다. 조립은 칸 행마다 금액을 원문 칸과 대조한다."""
        for cell in cells:
            for field in _AMOUNT_FIELDS:
                if lease.get(field) is not None:
                    self._set(cell, field, lease[field], _row_ids(lease))

    def _quota(self, at: int, quota: dict, cells: list[tuple[str, str | None]], by_type: bool) -> list[SharedCount]:
        kind, count, row_ids = quota.get("kind"), quota.get("count"), _row_ids(quota)
        if not isinstance(kind, str) or kind not in _CELL_FIELDS or not cells:
            self.warnings.append(f"공급 단계 응답 quotas[{at}]의 종류(kind)나 멤버를 읽지 못해 그 수량을 뺐습니다.")
            return []
        types = list(dict.fromkeys(owner for owner, _ in cells))
        whole_types = all(self._type_cells(key) <= set(cells) for key in types)
        if len(cells) == 1 and not (by_type and kind == "WINNER"):
            # 칸 하나의 수다. 그 칸이 주택형의 유일한 칸이면 주택형 모집수이기도 하다(LH 모집수 검산이 행 모집수를 본다).
            self._set(cells[0], _CELL_FIELDS[kind], count, row_ids)
            if kind == "WINNER" and whole_types:
                self._set(cells[0], "totalSupplyHouseholdCount", count, row_ids)
            return []
        if kind == "WINNER" and whole_types and len(types) == 1:
            # 주택형 줄에 대상 구분 없이 적힌 모집수다. 그 주택형 모든 칸 행이 같은 행 모집수를 갖는다.
            for cell in self._type_cells(types[0]):
                self._set(cell, "totalSupplyHouseholdCount", count, row_ids)
            return []
        if kind == "WINNER" and whole_types:
            # 여러 주택형이 함께 쓰는 모집 칸(49A·49B·49C 103)이다. 주택형마다 칸 행 하나만 가리켜도 그 주택형 모집수를 가리킨다.
            firsts = [self.items[self.cell_at[min(self._type_cells(key), key=self.cell_at.get)]] for key in types]
            return [SharedCount("totalSupplyHouseholdCount", count, firsts, row_ids)]
        if len(types) == 1:
            # 한 주택형 안에서 대상 여럿이 함께 쓰는 수량 칸(16A 청년 소득 구간 둘의 우선공급 19)의 행은 그 대상들의 행이기도 하다.
            # 그 행에만 대상 이름(청년)이 적혀 있어 원문 대조가 이 행으로 대상 이름을 확인한다.
            for cell in cells:
                item = self.items[self.cell_at[cell]]
                item.row_ids = list(dict.fromkeys([*item.row_ids, *row_ids]))
        return [SharedCount(_CELL_FIELDS[kind], count, [self.items[self.cell_at[cell]] for cell in cells], row_ids)]


def _summed(quotas: list[dict], members: list[list], by_type: list[bool]) -> list[tuple[int, dict, list, bool]]:
    """같은 칸(주택형×대상)을 함께 가리키는 같은 종류의 수량 칸들은 한 수의 부분이라 더해 수량 하나로 둔다.
    모집수를 우선공급·일반공급 열로 나눈 표(49번 16A: 대학생 우선 19, 청년 우선 19, 대학생·청년 일반 39)가 그렇다. 모집수는
    두 열의 합이고(사용자 결정), 병합된 일반 칸 때문에 칸마다 더할 수 없으면 묶인 칸들의 공통 수(77)가 된다. 원문 대조가 합을 확인한다.
    주택형 줄에 대상 구분 없이 적힌 수(행 모집수)는 대상 칸의 수와 더하지 않는다. 수가 정수가 아닌 칸도 더하지 않고 따로 둔다."""
    parent = list(range(len(quotas)))

    def root(at: int) -> int:
        while parent[at] != at:
            at = parent[at]
        return at

    def summable(at: int) -> bool:
        count = quotas[at].get("count")
        return bool(members[at]) and isinstance(count, int) and not isinstance(count, bool)

    for at in range(len(quotas)):
        for other in range(at):
            if summable(at) and summable(other) and quotas[at].get("kind") == quotas[other].get("kind") \
                    and by_type[at] == by_type[other] and set(members[at]) & set(members[other]):
                parent[root(at)] = root(other)
    parts: dict[int, list[int]] = {}
    for at in range(len(quotas)):
        parts.setdefault(root(at), []).append(at)
    summed = []
    for first, group in sorted(parts.items()):
        if len(group) == 1:
            summed.append((first, quotas[first], members[first], by_type[first]))
            continue
        quota = {"kind": quotas[first].get("kind"), "count": sum(quotas[at]["count"] for at in group),
                 "rowIds": list(dict.fromkeys(row_id for at in group for row_id in _row_ids(quotas[at])))}
        cells = list(dict.fromkeys(cell for at in group for cell in members[at]))
        summed.append((first, quota, cells, all(by_type[at] for at in group)))
    return summed


def _key(value: dict) -> str | None:
    key = value.get("key")
    return key if isinstance(key, str) and key else None


def _row_ids(value: dict) -> list[str]:
    ids = value.get("rowIds")
    return list(dict.fromkeys(item for item in ids if isinstance(item, str))) if isinstance(ids, list) else []
