"""Spring이 input.lhSupplyRows에 담아 보낸 LH 공급 API 행. 공급 단계 프롬프트의 틀이 되고,
결과의 lhSourceRow(프롬프트 목록 번호)를 Spring의 LH 행 식별자로 바꾼다."""

from dataclasses import dataclass

from ai_worker import checks


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
