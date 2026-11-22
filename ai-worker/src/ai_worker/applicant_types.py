"""형명 끝에 신청자 표기만 다른 주택형('29'와 '29 (주거약자)')을 주택형 하나와 공급대상 여럿으로 합친다.

공급대상 열이 없는 공급표는 신청자 구분을 주택형 칸에 적는다. 그대로 두면 '29 (주거약자)'가 주택형이 되고 공급대상은 '전체' 하나라
신청자격이 주거약자용 모집을 일반 모집과 같은 조건으로 판정한다. 공고 공급표 규칙은 주택형이 (단지, 신청형)마다 하나이고 신청자 묶음은
공급대상이다(docs/features/admin/guide.md). 그래서 같은 단지에서 표기를 뗀 형이 같은 단위들을 표기 없는 단위 하나로 합치고, 표기는
공급대상 이름이 된다. 표기 없는 단위의 대상 이름은 서버 ApplicantLabels와 같다: 국민임대·영구임대는 '일반', 그 밖은 '주거약자 외'
(표기가 둘 이상이면 '그 밖의 대상'). 일반공급이 아닌 줄을 '일반'이라 부르면 신청자격이 대상 확인 없이 일반공급으로 판정하기 때문이다.

합치는 것은 단위마다 공급대상이 하나이고(대상별 수와 단위 나머지가 섞이지 않게) 모집수를 하나로 읽었으며, 여러 칸이 함께 쓰는 수량이 없고
면적·입주 연월이 같거나 비었으며 건설호수를 모든 행이 적었거나 모두 비운 경우뿐이다. 건설호수는 행마다 따로 지은 집이라 더한다.
그 밖의 모양은 그대로 두고 관리자가 보정 화면에서 고친다.
"""

import re

from ai_worker import checks
from ai_worker.supply_sheet import EVERYONE, TOTAL, Cite, Finding, SupplySheet, Unit

# 행마다 같아야 하는 면적. 건설호수(BUILT)는 행마다 따로 센 수라 더한다.
SAME_FIELDS = ("exclusiveArea", "commonArea", "supplyArea")
BUILT = "constructedHouseholdCount"

GENERAL = "일반"
OTHERS = "그 밖의 대상"
GENERAL_SUPPLY = ("NATIONAL_RENTAL", "PERMANENT_RENTAL")
_MARKED = re.compile(r"(?P<base>.*?\S)\s*[(\[]\s*(?P<mark>[^()\[\]]*[가-힣][^()\[\]]*?)\s*[)\]]")


def fold(sheet: SupplySheet, job_input: dict | None) -> None:
    """sheet의 단위를 제자리에서 합친다. 표기 없는 단위의 대상 이름은 작업 입력의 임대유형(rentalType)으로 정한다. 서버가 임대유형을
    모르면(수집 때 정하지 못한 공고) 없고, 그때는 일반공급이라 단정하지 않는 '주거약자 외'를 쓴다."""
    rental_type = (job_input or {}).get("rentalType")
    groups: dict[tuple[str, str], list[Unit]] = {}
    for unit in sheet.units:
        if isinstance(unit.name, str) and unit.name.strip():
            groups.setdefault((_squash(_text(unit.complex_name)), _squash(_parts(unit.name)[0])), []).append(unit)
    for members in groups.values():
        if _foldable(sheet, members):
            _fold(sheet, members, rental_type)


def _foldable(sheet: SupplySheet, members: list[Unit]) -> bool:
    plain = [unit for unit in members if not _parts(unit.name)[1]]
    if len(members) < 2 or len(plain) != 1:
        return False
    shared = {id(owner) for item in sheet.shared for owner in item.owners}
    areas = {checks.area_key(unit.values.get("exclusiveArea")) for unit in members}
    return (len(areas) == 1 and None not in areas
            and all(_same_or_blank(members, name) for name in (*SAME_FIELDS[1:], "expectedMoveInMonth"))
            and _summable(members)
            and all(len(unit.entries) == 1 and not unit.parts and not unit.house_types
                    and id(unit) not in shared and id(unit.entries[0]) not in shared
                    and _text(unit.entries[0].label).strip() in ("", EVERYONE)
                    and _count(unit) is not None
                    for unit in members))


def _same_or_blank(members: list[Unit], name: str) -> bool:
    """면적·입주 연월은 같은 형의 값이라 행마다 같거나 비어 있어야 한다. 다르면 다른 집일 수 있어 합치지 않는다."""
    values = {checks.area_key(value) if name in SAME_FIELDS else value
              for unit in members if (value := unit.values.get(name)) is not None}
    return len(values) <= 1


def _summable(members: list[Unit]) -> bool:
    """건설호수는 신청자 표기마다 따로 지은 집의 수라(29 167호, 29 (주거약자) 15호) 합친 형의 수는 그 합이다. 일부 행만 적었으면
    합을 알 수 없어 합치지 않는다."""
    counts = [unit.values.get(BUILT) for unit in members]
    return all(count is None for count in counts) or all(_integer(count) for count in counts)


def _count(unit: Unit) -> int | None:
    """단위의 모집수. 대상 하나뿐인 단위는 모집수를 단위(TOTAL)나 그 대상(supplyHouseholdCount)에 둔다. 둘이 다르면 정하지 않는다."""
    counts = {value for value in (unit.values.get(TOTAL), unit.entries[0].values.get("supplyHouseholdCount")) if _integer(value)}
    return counts.pop() if len(counts) == 1 else None


def _fold(sheet: SupplySheet, members: list[Unit], rental_type: str | None) -> None:
    target = next(unit for unit in members if not _parts(unit.name)[1])
    marks = list(dict.fromkeys(_parts(unit.name)[1] for unit in members if unit is not target))
    plain_label = GENERAL if rental_type in GENERAL_SUPPLY else f"{marks[0]} 외" if len(marks) == 1 else OTHERS
    total = 0
    for unit in members:
        entry = unit.entries[0]
        count = _count(unit)
        entry.label = _parts(unit.name)[1] or plain_label
        entry.values["supplyHouseholdCount"] = count
        total += count
        # 단위 모집수가 대상의 모집수가 됐다. 그 근거·미확인도 대상으로 옮기고, 나머지는 남는 단위로 옮긴다.
        sheet.evidence[:] = [cite for old in sheet.evidence for cite in _moved_cite(old, unit, target)]
        sheet.findings[:] = [_moved_finding(old, unit, target) for old in sheet.findings]
        if unit is not target:
            target.entries.append(entry)
    target.values[TOTAL] = total
    if _integer(target.values.get(BUILT)):
        target.values[BUILT] = sum(unit.values[BUILT] for unit in members)
    for name in (*SAME_FIELDS[1:], "expectedMoveInMonth"):
        target.values.setdefault(name, None)
        if target.values[name] is None:
            target.values[name] = next((unit.values.get(name) for unit in members if unit.values.get(name) is not None), None)
    sheet.units[:] = [unit for unit in sheet.units if unit is target or unit not in members]


def _moved_cite(cite: Cite, unit: Unit, target: Unit) -> list[Cite]:
    if cite.owner is not unit:
        return [cite]
    entry = unit.entries[0]
    if cite.field == TOTAL:
        return [Cite(entry, "supplyHouseholdCount", cite.page, cite.quote)]
    if not cite.field:
        # 행 전체 근거는 그 대상의 수량·임대조건 근거이자 남는 주택형의 근거다.
        return list(dict.fromkeys([Cite(target, "", cite.page, cite.quote), Cite(entry, "", cite.page, cite.quote)]))
    return [Cite(target, cite.field, cite.page, cite.quote)]


def _moved_finding(finding: Finding, unit: Unit, target: Unit) -> Finding:
    if finding.owner is not unit:
        return finding
    if finding.field == TOTAL:
        return Finding(unit.entries[0], "supplyHouseholdCount", finding.reason)
    return Finding(target, finding.field, finding.reason)


def _parts(name: str) -> tuple[str, str]:
    """(표기를 뗀 형명, 신청자 표기). 표기가 없으면 표기는 빈 문자열이다."""
    found = _MARKED.fullmatch(name.strip())
    return (found["base"], found["mark"].strip()) if found else (name.strip(), "")


def _squash(value: str) -> str:
    return "".join(value.split()).removesuffix("형")


def _text(value: object) -> str:
    return value if isinstance(value, str) else ""


def _integer(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)
