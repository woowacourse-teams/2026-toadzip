"""Spring이 input.sourceSupplyRows에 담아 보낸 기관 공급 원천 행(공급행 틀)을 읽고, 결과 공급행마다 그 행이 나온
원천 행의 식별자(sourceRow)를 채운다. 화면은 목록 위치가 아니라 이 식별자로 공급 보정 작업실의 행을 맞춘다.

LH 행은 공급 단계 모델에 틀로 주고, 모델이 쓴 목록 번호(1부터)를 그 행의 식별자로 바꾼다.
GH 행은 모델에 주지 않는다. 묶은 공급행을 지구명·주택형으로 코드가 맞추고, 하나로 정할 수 없으면 비워 관리자가 확인하게 한다.
배포 전에 넣은 작업은 옛 키(lhSupplyRows, LH 행만)로 온다. 두 키가 함께 오면(옛 워커 호환으로 Spring이 한 릴리스 동안 함께 씀) 새 키만 읽는다.
"""

import re
import unicodedata
from dataclasses import dataclass

from ai_worker import checks

_LH = "LH"
_GH = "GH"
_WHITESPACE = re.compile(r"\s+")
_GH_PROGRAM = re.compile(r"경기행복주택$")
# 화면의 equalArea와 같은 허용 오차다.
_AREA_TOLERANCE = 0.01


@dataclass(frozen=True)
class SourceSupplyRow:
    """원천 공급 한 행. 값은 Spring이 보낸 문자열 그대로다(LH는 LH가 보낸 값, GH는 GH 공급행 틀의 값).
    row_identifier는 공급 보정 작업실이 그 행을 찾는 식별자다(LH:{panId}:SUPPLY:{order}, GH 틀의 행 키).
    식별자를 담기 전에 넣은 LH 작업이면 없다."""

    source: str
    row_identifier: str | None
    complex_label: str
    type_name: str
    exclusive_area: str
    supply_area: str
    supplied_unit_count: str

    @classmethod
    def of(cls, row: dict, source: str | None = None) -> "SourceSupplyRow":
        return cls(source or row["source"], row.get("rowIdentifier"), row.get("complexLabel") or "", row["typeName"],
                   row.get("exclusiveArea") or "", row.get("supplyArea") or "", row.get("suppliedUnitCount") or "")

    def count(self) -> int | None:
        try:
            return int(self.supplied_unit_count)
        except (TypeError, ValueError):
            return None


class SourceFrame:
    """한 작업의 원천 공급 행. lh_rows는 공급 단계 모델에 틀로 줄 행이고, link가 결과 공급행에 원천 행 식별자를 채운다."""

    def __init__(self, rows: list[SourceSupplyRow]):
        self._lh = [row for row in rows if row.source == _LH]
        self._gh = _GhFrame([row for row in rows if row.source == _GH])

    @classmethod
    def read(cls, job_input: dict | None) -> "SourceFrame":
        job_input = job_input or {}
        if "sourceSupplyRows" in job_input:
            return cls([SourceSupplyRow.of(row) for row in job_input["sourceSupplyRows"]])
        return cls([SourceSupplyRow.of(row, _LH) for row in job_input.get("lhSupplyRows", [])])

    @property
    def lh_rows(self) -> list[SourceSupplyRow]:
        """공급 단계 모델에 틀로 줄 LH 행. 이 행이 있으면 모델이 번호로 공급행을 나누고 맞추므로 공급표를 모델 단계로 읽는다."""
        return self._lh

    def __len__(self) -> int:
        return len(self._lh) + len(self._gh)

    def link(self, tree: dict) -> list[str]:
        """결과 공급행의 sourceRow를 원천 행 식별자로 채우고, 맞추지 못한 행의 경고를 돌려준다.
        LH 번호 검산(번호 없음·범위 밖·모집수)은 checks가 이미 했다."""
        rows = checks.objects(tree["data"].get("supplyRows"))
        _link_lh_numbers(rows, self._lh)
        return self._gh.link(rows)


def _link_lh_numbers(rows: list[dict], lh_rows: list[SourceSupplyRow]) -> None:
    """모델은 공급행이 나온 LH 행을 프롬프트 목록의 번호(1부터)로 쓴다. 번호가 없거나 범위를 벗어나면(LH 행이 없을 때 포함) null이다."""
    for row in rows:
        if "sourceRow" not in row:
            continue
        number = row["sourceRow"]
        valid = isinstance(number, int) and not isinstance(number, bool) and 1 <= number <= len(lh_rows)
        row["sourceRow"] = lh_rows[number - 1].row_identifier if valid else None


class _GhFrame:
    """GH 공급행 틀. 공급행마다 같은 지구와 같은 주택형(틀 이름의 첫 낱말이나 이름 전체)인 틀 행을 찾고,
    여럿이면 전용면적으로 좁힌다. 한 공급행에 한 틀 행, 한 틀 행에 한 공급행일 때만 식별자를 채운다.
    화면은 그 틀 행을 이 공급행 값으로 고치므로 둘 이상이 한 틀 행을 가리키면 모두 비운다.
    원천 지구명("안양관양 경기행복주택")과 공고문 표기("안양 관양")가 달라, 공백과 공통 꼬리를 뺀 이름이 같거나
    PDF 이름이 틀 지구명에 들어 있으면서 그런 지구가 하나뿐일 때 같은 지구로 본다.
    맞추지 못한 행은 행마다가 아니라 한 줄로 모아 알린다(실제 공고에서 수십 줄이 되어 다른 경고가 묻히지 않게)."""

    def __init__(self, rows: list[SourceSupplyRow]):
        self._rows = [row for row in rows if row.row_identifier]

    def __len__(self) -> int:
        return len(self._rows)

    def link(self, supply_rows: list[dict]) -> list[str]:
        if not self._rows:
            return []
        warnings: list[str] = []
        claims: dict[int, list[int]] = {}
        unmatched: list[str] = []
        for index, row in enumerate(supply_rows):
            row["sourceRow"] = None
            frame_index = self._candidate(index, row, warnings, unmatched)
            if frame_index is not None:
                claims.setdefault(frame_index, []).append(index)
        for frame_index, indexes in claims.items():
            frame = self._rows[frame_index]
            if len(indexes) == 1:
                supply_rows[indexes[0]]["sourceRow"] = frame.row_identifier
                continue
            paths = ", ".join(f"supplyRows[{index}]" for index in indexes)
            warnings.append(f"{paths}: 같은 GH 공급행 틀({_label(frame)})에 맞아 하나로 정하지 못함")
        if unmatched:
            warnings.append(f"GH 공급행 틀과 맞추지 못한 PDF 공급행 {len(unmatched)}개: {', '.join(unmatched)}")
        missing = [_label(frame) for frame_index, frame in enumerate(self._rows) if frame_index not in claims]
        if missing:
            warnings.append(f"PDF에서 찾지 못한 GH 공급행 틀 {len(missing)}개: {', '.join(missing)}")
        return warnings

    def _candidate(self, index: int, row: dict, warnings: list[str], unmatched: list[str]) -> int | None:
        complex_name = _complex_name(row)
        housing_type = row.get("sourceHousingTypeName") if isinstance(row.get("sourceHousingTypeName"), str) else ""
        district = self._district(complex_name)
        matches = [frame_index for frame_index, frame in enumerate(self._rows)
                   if frame.complex_label == district and _same_type(housing_type, frame.type_name)]
        if len(matches) > 1:
            matches = [frame_index for frame_index in matches
                       if _same_area(row.get("exclusiveArea"), self._rows[frame_index].exclusive_area)] or matches
        if len(matches) == 1:
            return matches[0]
        label = f"{complex_name} {housing_type}".strip()
        if matches:
            warnings.append(f"supplyRows[{index}].sourceRow: GH 공급행 틀에 같은 지구·주택형 행이 {len(matches)}개라 "
                            f"하나로 정하지 못함({label})")
        else:
            unmatched.append(label)
        return None

    def _district(self, complex_name: str) -> str | None:
        """PDF 단지명에 맞는 틀의 지구명. 같은 이름이 없으면 PDF 이름이 들어 있는 지구가 하나뿐일 때만 고른다.
        PDF 이름이 틀 지구명을 품는 방향("광교원천" ⊃ "광교")은 다른 지구일 수 있어 허용하지 않는다."""
        name = _district_key(complex_name)
        if not name:
            return None
        districts = list(dict.fromkeys(frame.complex_label for frame in self._rows))
        exact = [district for district in districts if _district_key(district) == name]
        found = exact or [district for district in districts
                          if name in _district_key(district)]
        return found[0] if len(found) == 1 else None


def _complex_name(row: dict) -> str:
    reference = row.get("complexReference")
    name = reference.get("sourceComplexName") if isinstance(reference, dict) else None
    return name if isinstance(name, str) else ""


def _same_type(pdf_type: str, frame_type: str) -> bool:
    """GH 틀 이름은 "36A 청년"처럼 주택형 뒤에 공급대상을 붙이므로 첫 낱말(주택형 코드)이나 이름 전체와 맞댄다."""
    if not _key(pdf_type):
        return False
    words = frame_type.split()
    return _type_key(pdf_type) in {_type_key(frame_type), _type_key(words[0]) if words else ""}


def _same_area(pdf_area: object, frame_area: str) -> bool:
    if not isinstance(pdf_area, (int, float)) or isinstance(pdf_area, bool):
        return False
    try:
        return abs(float(frame_area) - pdf_area) < _AREA_TOLERANCE
    except ValueError:
        return False


def _key(value: str) -> str:
    return _WHITESPACE.sub("", unicodedata.normalize("NFKC", value)).upper()


def _type_key(value: str) -> str:
    """원천은 여러 주택형을 "24A,B,C"처럼, 공고문 표는 "24ABC"처럼 쓴다."""
    return _key(value).replace(",", "")


def _district_key(value: str) -> str:
    """원천 지구명 끝의 사업 이름("경기행복주택")은 공고문 표 단지명에 없다."""
    return _GH_PROGRAM.sub("", _key(value))


def _label(frame: SourceSupplyRow) -> str:
    return f"{frame.complex_label} {frame.type_name}".strip()
