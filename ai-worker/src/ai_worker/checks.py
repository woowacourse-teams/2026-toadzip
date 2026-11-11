"""모델이 돌려준 추출 JSON을 관리 화면에 넘기기 전에 다듬는다.

모델은 첨부한 쪽 기준 번호를 쓰므로 원본 쪽 번호로 바꾼다. 자주 어기는 규칙과 검산 불일치는 값을 고치지 않고
경고로만 남겨 관리자가 보정 화면에서 확인하게 한다. 행 모집수와 대상별 합계처럼 화면이 이미 검산하는 것은 여기서 다시 하지 않는다.
"""

import json
import re
from functools import lru_cache
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from typing import TYPE_CHECKING

from ai_worker.extraction import MODEL_RESPONSE_INVALID, PermanentError

if TYPE_CHECKING:
    from ai_worker.source_frame import SourceSupplyRow

INVALID_CODE = MODEL_RESPONSE_INVALID
_DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
# 정수(24, 39)는 흔한 주택형 이름이라 소수점이 있을 때만 면적으로 본다(화면의 areaOnlyTypeName과 같은 기준).
_AREA_ONLY = re.compile(r"\d+\.\d+")
_TARGET_NOTATION = re.compile(r"「|\s\(")
_WHITESPACE = re.compile(r"\s+")
# PDF 글꼴에 따라 가운뎃점이 여러 글자로 뽑힌다. 모델이 그중 하나로 옮겨도 같은 글로 본다.
_MIDDLE_DOTS = re.compile(r"[ㆍ․‧•∙・]")
_DATE_FIELDS = ("postedDate", "applicationStartDate", "applicationEndDate", "winnerAnnouncementDate")


def json_object(reply: str) -> dict:
    """응답 뒤에 글이 더 붙는 경우가 있어(평가의 Extra data 실패) 첫 JSON 객체만 읽는다.
    NaN·Infinity는 JSON이 아니어서 화면이 결과를 읽지 못하므로 받지 않는다."""
    start = reply.find("{")
    if start < 0:
        raise PermanentError(INVALID_CODE, "AI 모델 응답에서 JSON을 찾지 못했습니다.")
    try:
        tree, _ = json.JSONDecoder(parse_constant=_reject_constant).raw_decode(reply, start)
    except (json.JSONDecodeError, ValueError) as error:
        raise PermanentError(INVALID_CODE, "AI 모델 응답을 JSON으로 읽지 못했습니다.") from error
    return tree


def first_json_object(reply: str, expected_data: dict[str, type] | None = None) -> dict:
    """본문·공급 단계 응답처럼 추출 결과를 data 아래에 두는 응답을 읽고, 그 단계가 맡은 칸이 있는지 확인한다."""
    tree = json_object(reply)
    data = tree.get("data")
    if not isinstance(data, dict):
        raise PermanentError(INVALID_CODE, "AI 모델 응답에 추출 결과(data)가 없습니다.")
    # 단계별 응답은 관리자 계약의 일부만 갖고, 합친 뒤 최종 구조를 다시 확인한다.
    expected = expected_data or {"announcement": dict, "supplyRows": list, "schedules": list}
    if any(not isinstance(data.get(name), value_type) for name, value_type in expected.items()):
        raise PermanentError(INVALID_CODE, "AI 모델 응답의 추출 단계 형식이 맞지 않습니다.")
    return tree


def _reject_constant(name: str):
    raise ValueError(f"JSON이 아닌 값: {name}")


def review(tree: dict, pages: list[int], texts: list[str], lh_rows: list["SourceSupplyRow"],
           rental_rows: int) -> list[str]:
    """tree의 쪽 번호를 원본 기준으로 바꾸고 경고를 돌려준다.
    texts는 원본 PDF 쪽마다 근거 인용문을 찾을 글(단계가 그 쪽을 다른 순서로 읽었으면 그 글도 이어 붙인 것), lh_rows는 참고 목록으로 준 LH API 행,
    rental_rows는 임대조건표에서 센 (주택형, 공급계층) 줄 수다."""
    lh_types = [row.type_name for row in lh_rows]
    warnings: list[str] = []
    evidence = remap_pages(objects(tree.get("evidence")), pages, warnings)
    remap_pages(objects(tree.get("sharedCounts")), pages, warnings)
    _check_quotes(evidence, texts, warnings)
    data = tree["data"]
    announcement = data["announcement"]
    _check_announcement(announcement, warnings)
    _check_complexes(announcement, objects(data.get("complexes")), warnings)
    rows = objects(data.get("supplyRows"))
    for index, row in enumerate(rows):
        _check_supply_row(row, f"supplyRows[{index}]", _text(announcement.get("postedDate")), set(lh_types), warnings)
    _check_lh_type_names(rows, evidence, set(lh_types), warnings)
    _check_lh_counts(rows, lh_rows, warnings)
    _check_target_count(rows, rental_rows, warnings)
    return warnings


def _check_lh_type_names(rows: list[dict], evidence: list[dict], lh_types: set[str], warnings: list[str]) -> None:
    """공급행 틀로 준 LH 주택형 이름(24A)을 PDF 공급 표 표기(24) 대신 옮겼는지 본다. 그 행의 근거 인용에 이름이 없으면 경고한다."""
    quotes = {item.get("path"): _WHITESPACE.sub("", _text(item.get("quote"))) for item in evidence}
    for index, row in enumerate(rows):
        housing_type, quote = _text(row.get("sourceHousingTypeName")), quotes.get(f"supplyRows[{index}]")
        # 공급대상 괄호는 병합 셀이라 표 한 줄 인용에 없을 수 있어 괄호 앞 표기만 대조한다(화면과 같은 기준).
        core = _WHITESPACE.sub("", housing_type).split("(")[0]
        if housing_type in lh_types and quote and core not in quote:
            warnings.append(f"supplyRows[{index}].sourceHousingTypeName: 근거 인용에 없는 LH 주택형 이름({housing_type})")


def remap_pages(items: list[dict], pages: list[int], warnings: list[str]) -> list[dict]:
    """원본 쪽 번호로 바꾼 항목을 돌려준다. 범위를 벗어난 항목은 그대로 두고 경고한다."""
    remapped = []
    for item in items:
        page = item.get("page")
        if not isinstance(page, int) or isinstance(page, bool) or not 1 <= page <= len(pages):
            warnings.append(f"{_label(item)}: 첨부한 쪽 범위를 벗어난 근거 쪽 번호({page})")
            continue
        item["page"] = pages[page - 1]
        remapped.append(item)
    return remapped


def missing_words(quote: str, page_text: str) -> list[str]:
    """quote의 낱말 가운데 쪽 글에 없는 것. 모델은 표 한 줄을 칸 순서대로 이어 인용하는데 PDF 글은 칸을 다른 순서로 뽑기도 해서,
    인용문 전체가 아니라 낱말마다 찾는다. 쪽 글의 공백과 줄바꿈은 무시한다."""
    compact = compact_text(page_text)
    return [word for word in _MIDDLE_DOTS.sub("·", quote).split() if word not in compact]


# 같은 쪽 글을 요건마다 다시 줄이지 않게 기억한다. 워커는 오래 떠 있으므로 한 공고의 쪽 수만큼만 기억한다.
@lru_cache(maxsize=256)
def compact_text(text: str) -> str:
    return _MIDDLE_DOTS.sub("·", _WHITESPACE.sub("", text))


def _check_quotes(evidence: list[dict], texts: list[str], warnings: list[str]) -> None:
    """근거 인용문의 낱말이 모두 그 쪽 원문에 있는지 본다. 없으면 값이 다른 쪽에서 왔거나 지어낸 것일 수 있다."""
    for item in evidence:
        page = item["page"]
        missing = missing_words(_text(item.get("quote")), texts[page - 1])
        if missing:
            warnings.append(f"{_label(item)}: 근거 인용문의 {', '.join(missing[:3])}이(가) {page}쪽 원문에 없음")


def _check_lh_counts(rows: list[dict], lh_rows: list["SourceSupplyRow"], warnings: list[str]) -> None:
    """LH API 행은 공급행을 정하지 않는다. 같은 단지에서 전용면적(소수 둘째 자리)이 공급행의 면적(세부 주택형 포함) 가운데
    하나와 같은 LH 행이 그 공급행에 붙는다. 신청자 구분으로 나뉜 여러 LH 행이 한 공급행에 붙으므로 모집수 합계를 PDF 모집수와 맞대고,
    다르면 경고만 한다(PDF와 API 중 하나를 확인할 일이지 PDF 값을 못 쓰는 것은 아니다). LH 행 수와 공급행 수가 다른 것은 오류가 아니다.
    면적이 없는 LH 행은 맞댈 수 없어 조용히 넘긴다."""
    groups: dict[tuple[str, Decimal], list["SourceSupplyRow"]] = {}
    for lh_row in lh_rows:
        area = area_key(lh_row.exclusive_area)
        if area is not None:
            groups.setdefault((lh_row.complex_label, area), []).append(lh_row)
    if not groups:
        return
    labels = list(dict.fromkeys(label for label, _ in groups))
    pdf_complexes = {_complex_key(_complex_name(row)) for row in rows}
    # 같은 단지에서 면적이 겹치는 PDF 행은 화면이 공급행 하나로 접는다(형명만 다른 26A·26B, 신청자 구분 행). 그 행들을 함께 맞댄다.
    clusters: list[tuple[str, list[Decimal], list[int]]] = []
    for index, row in enumerate(rows):
        label = _lh_complex_label(_complex_name(row), labels, len(pdf_complexes) == 1)
        areas = list(dict.fromkeys(_row_areas(row)))
        if label is None or not areas:
            continue
        touching = [cluster for cluster in clusters if cluster[0] == label and set(cluster[1]) & set(areas)]
        merged = (label, list(dict.fromkeys([*(area for cluster in touching for area in cluster[1]), *areas])),
                  sorted([index, *(at for cluster in touching for at in cluster[2])]))
        clusters = [cluster for cluster in clusters if cluster not in touching] + [merged]
    for label, areas, indexes in sorted(clusters, key=lambda cluster: cluster[2][0]):
        counts = [rows[at].get("totalSupplyHouseholdCount") for at in indexes]
        # 행 모집수가 비어 있으면 공통 모집수(sharedCounts)로 묶인 행이라 맞대지 않는다.
        if not all(isinstance(count, int) and not isinstance(count, bool) for count in counts):
            continue
        attached = [lh_row for area in areas for lh_row in groups.get((label, area), [])]
        expected = [lh_row.count() for lh_row in attached]
        # LH API는 모집수를 모르면 0을 보낸다. 하나라도 모르면 합계를 맞댈 수 없다.
        if not attached or not all(expected):
            continue
        total, count = sum(expected), sum(counts)
        if total == count:
            continue
        shown = ", ".join(str(area) for area in areas)
        head = (f"supplyRows[{indexes[0]}].totalSupplyHouseholdCount: 같은 전용면적({shown}) LH API 공급행 "
                f"{len(attached)}줄의 모집수 합계 {total}와 ")
        if len(indexes) == 1:
            warnings.append(f"{head.removesuffix('와 ')}와 다름({count})")
        else:
            members = ", ".join(f"supplyRows[{at}]" for at in indexes)
            warnings.append(f"{head}같은 면적 PDF 행 {len(indexes)}개({members})의 합 {count}가 다름")


def area_key(value: object) -> Decimal | None:
    """전용면적 비교 키. 소수 셋째 자리에서 반올림한다(HALF_UP, 백엔드 HousingTypeAreas와 같은 기준).
    round()는 은행가 반올림으로 26.125를 26.12로, float를 그대로 Decimal로 바꾸면 이진 오차로 26.365를 26.36으로 만든다."""
    if isinstance(value, bool) or not isinstance(value, (int, float, str)):
        return None
    try:
        area = Decimal(str(value).strip())
    except InvalidOperation:
        return None
    return area.quantize(Decimal("0.01"), ROUND_HALF_UP) if area.is_finite() else None


def _row_areas(row: dict) -> list[Decimal]:
    areas = [area_key(row.get("exclusiveArea")), *(area_key(subtype.get("exclusiveArea")) for subtype in objects(row.get("subtypes")))]
    return list(dict.fromkeys(area for area in areas if area is not None))


def _lh_complex_label(name: str, labels: list[str], single_pdf_complex: bool) -> str | None:
    """PDF 단지명에 맞는 LH 단지 이름. LH 이름은 "인천부평(도시재생뉴딜) 행복주택"처럼 PDF 표기보다 길어,
    같은 이름이 없으면 PDF 이름이 들어 있는 LH 단지가 하나뿐일 때만 고른다. 양쪽 모두 단지가 하나면 그 단지다."""
    if len(labels) == 1 and single_pdf_complex:
        return labels[0]
    key = _complex_key(name)
    if not key:
        return None
    found = [label for label in labels if _complex_key(label) == key] or [
        label for label in labels if key in _complex_key(label)]
    return found[0] if len(found) == 1 else None


def _complex_name(row: dict) -> str:
    reference = row.get("complexReference")
    return _text(reference.get("sourceComplexName")) if isinstance(reference, dict) else ""


def _complex_key(name: str) -> str:
    return _WHITESPACE.sub("", name)


def _check_target_count(rows: list[dict], rental_rows: int, warnings: list[str]) -> None:
    """추론을 낮추면 모델이 한 공급계층(예: 기존거주자)을 모든 행에서 통째로 빠뜨린다. 그래도 값은 맞아 보여
    다른 검산에 걸리지 않으므로, 공급대상 수가 임대조건표 줄 수보다 적으면 경고한다."""
    targets = sum(len(objects(row.get("targets"))) for row in rows)
    if targets < rental_rows:
        warnings.append(f"supplyRows: 공급대상 {targets}개가 임대조건표 {rental_rows}줄보다 적어 빠진 대상이 있을 수 있음")


def _check_announcement(announcement: dict, warnings: list[str]) -> None:
    for field in _DATE_FIELDS:
        value = _text(announcement.get(field))
        if value and not _DATE.fullmatch(value):
            warnings.append(f"announcement.{field}: 날짜 형식이 아님({value})")


def _check_complexes(announcement: dict, complexes: list[dict], warnings: list[str]) -> None:
    title = _text(announcement.get("name"))
    for index, complex_ in enumerate(complexes):
        if title and title == _text(complex_.get("sourceComplexName")):
            warnings.append(f"complexes[{index}].sourceComplexName: 단지명이 공고 제목 전체와 같음")


def _check_supply_row(row: dict, path: str, posted_date: str, lh_types: set[str], warnings: list[str]) -> None:
    housing_type = _text(row.get("sourceHousingTypeName"))
    if housing_type and _AREA_ONLY.fullmatch(housing_type) and housing_type not in lh_types:
        warnings.append(f"{path}.sourceHousingTypeName: 면적 숫자만 있음({housing_type})")
    if row.get("commonArea") is not None and row.get("supplyArea") is not None:
        warnings.append(f"{path}.supplyArea: 공용면적이 있는데 공급면적도 채워짐")
    move_in = _text(row.get("expectedMoveInMonth"))
    if move_in and len(posted_date) >= 7 and move_in < posted_date[:7]:
        warnings.append(f"{path}.expectedMoveInMonth: 공고일보다 이른 연월({move_in})")
    for index, target in enumerate(objects(row.get("targets"))):
        name = _text(target.get("target"))
        if _TARGET_NOTATION.search(name):
            warnings.append(f"{path}.targets[{index}].target: 표기 규칙 위반({name})")


def _label(item: dict) -> str:
    if "path" in item:
        return _text(item.get("path"))
    return json.dumps(item.get("paths"), ensure_ascii=False, separators=(",", ":"))


def objects(value: object) -> list[dict]:
    """모델이 배열 자리에 다른 값을 넣어도 검사가 멈추지 않게, 객체 항목만 본다."""
    return [item for item in value if isinstance(item, dict)] if isinstance(value, list) else []


def _text(value: object) -> str:
    return value if isinstance(value, str) else ""


# 관리자 화면(extraction.ts validPath)이 읽는 경로 형식. 하나라도 어긋나면 화면이 추출 결과 전체를 거부한다.
_NOTE_PATH = re.compile(r"[A-Za-z][A-Za-z0-9]*(?:\[(?:\d+|\*)\]|\.[A-Za-z][A-Za-z0-9]*)*")


def normalize_note_paths(tree: dict, warnings: list[str]) -> None:
    """모델이 미확인·해당없음 경로를 complexes[].parkingCount처럼 쓰면 화면이 결과 전체를 읽지 못한다.
    빈 순번은 전체([*])로 바꾼다. 그래도 읽을 수 없는 미확인 경로는 읽을 수 있는 앞부분까지 넓히고(범위가 넓어져도 보수적이다),
    해당없음 경로는 넓히면 그 아래 값이 모두 반영에서 빠지므로 빼고 경고로 남긴다."""
    for key, holder, widen in (("unresolvedFields", tree.get("data", {}), True), ("notApplicable", tree, False)):
        kept = []
        for item in objects(holder.get(key)):
            original = item.get("path")
            path = re.sub(r"^\$\.?|^data\.", "", original.strip()).replace("[]", "[*]") if isinstance(original, str) else ""
            usable = path if _NOTE_PATH.fullmatch(path) else (_readable_prefix(path) if widen else "")
            if not usable:
                warnings.append(f"{key}: 경로를 읽을 수 없는 항목을 뺐습니다({original}: {item.get('reason')})")
                continue
            if usable != path:
                warnings.append(f"{key}: 읽을 수 없는 경로 {original}를 {usable}로 넓혔습니다.")
            kept.append({**item, "path": usable})
        if key in holder or kept:
            holder[key] = kept


def _readable_prefix(path: str) -> str:
    """경로 앞에서 온전한 마디(. 이나 [ 앞)까지만 남긴다."""
    match = _NOTE_PATH.match(path)
    prefix = match.group() if match else ""
    if prefix and path[len(prefix):][:1] not in ".[":
        prefix = re.sub(r"(?:\.[A-Za-z0-9]*|\[[^\]]*)$", "", prefix)
    return prefix


def deduplicate(items: list[dict]) -> list[dict]:
    seen, result = set(), []
    for item in items:
        marker = json.dumps(item, ensure_ascii=False, sort_keys=True)
        if marker not in seen:
            seen.add(marker)
            result.append(item)
    return result
