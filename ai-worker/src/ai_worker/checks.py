"""모델이 돌려준 추출 JSON을 관리 화면에 넘기기 전에 다듬는다.

모델은 첨부한 쪽 기준 번호를 쓰므로 원본 쪽 번호로 바꾼다. 자주 어기는 규칙과 검산 불일치는 값을 고치지 않고
경고로만 남겨 관리자가 보정 화면에서 확인하게 한다. 행 모집수와 대상별 합계처럼 화면이 이미 검산하는 것은 여기서 다시 하지 않는다.
"""

import json
import re

from ai_worker.extraction import MODEL_RESPONSE_INVALID, PermanentError

INVALID_CODE = MODEL_RESPONSE_INVALID
DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
# 정수(24, 39)는 흔한 주택형 이름이라 소수점이 있을 때만 면적으로 본다(화면의 areaOnlyTypeName과 같은 기준).
AREA_ONLY = re.compile(r"\d+\.\d+")
TARGET_NOTATION = re.compile(r"「|\s\(")
WHITESPACE = re.compile(r"\s+")
DATE_FIELDS = ("postedDate", "applicationStartDate", "applicationEndDate", "winnerAnnouncementDate")


def first_json_object(reply: str, expected_data: dict[str, type] | None = None) -> dict:
    """응답 뒤에 글이 더 붙는 경우가 있어(평가의 Extra data 실패) 첫 JSON 객체만 읽는다.
    NaN·Infinity는 JSON이 아니어서 화면이 결과를 읽지 못하므로 받지 않는다."""
    start = reply.find("{")
    if start < 0:
        raise PermanentError(INVALID_CODE, "AI 모델 응답에서 JSON을 찾지 못했습니다.")
    try:
        tree, _ = json.JSONDecoder(parse_constant=reject_constant).raw_decode(reply, start)
    except (json.JSONDecodeError, ValueError) as error:
        raise PermanentError(INVALID_CODE, "AI 모델 응답을 JSON으로 읽지 못했습니다.") from error
    data = tree.get("data")
    if not isinstance(data, dict):
        raise PermanentError(INVALID_CODE, "AI 모델 응답에 추출 결과(data)가 없습니다.")
    # 단계별 응답은 관리자 계약의 일부만 갖고, 합친 뒤 최종 구조를 다시 확인한다.
    expected = expected_data or {"announcement": dict, "supplyRows": list, "schedules": list}
    if any(not isinstance(data.get(name), value_type) for name, value_type in expected.items()):
        raise PermanentError(INVALID_CODE, "AI 모델 응답의 추출 단계 형식이 맞지 않습니다.")
    return tree


def reject_constant(name: str):
    raise ValueError(f"JSON이 아닌 값: {name}")


def review(tree: dict, pages: list[int], texts: list[str], lh_types: list[str], lh_counts: list[int | None],
           rental_rows: int) -> list[str]:
    """tree의 쪽 번호를 원본 기준으로 바꾸고 경고를 돌려준다.
    texts는 원본 PDF 전체 쪽 텍스트, lh_types·lh_counts는 공급행 틀로 준 LH API 행의 주택형과 모집수,
    rental_rows는 임대조건표에서 센 (주택형, 공급계층) 줄 수다."""
    warnings: list[str] = []
    evidence = remap_pages(objects(tree.get("evidence")), pages, warnings)
    remap_pages(objects(tree.get("sharedCounts")), pages, warnings)
    check_quotes(evidence, texts, warnings)
    data = tree["data"]
    announcement = data["announcement"]
    check_announcement(announcement, warnings)
    check_complexes(announcement, objects(data.get("complexes")), warnings)
    rows = objects(data.get("supplyRows"))
    for index, row in enumerate(rows):
        check_supply_row(row, f"supplyRows[{index}]", text(announcement.get("postedDate")), set(lh_types), warnings)
    check_lh_type_names(rows, evidence, set(lh_types), warnings)
    check_lh_counts(rows, lh_types, lh_counts, warnings)
    check_target_count(rows, rental_rows, warnings)
    return warnings


def check_lh_type_names(rows: list[dict], evidence: list[dict], lh_types: set[str], warnings: list[str]) -> None:
    """공급행 틀로 준 LH 주택형 이름(24A)을 PDF 공급 표 표기(24) 대신 옮겼는지 본다. 그 행의 근거 인용에 이름이 없으면 경고한다."""
    quotes = {item.get("path"): WHITESPACE.sub("", text(item.get("quote"))) for item in evidence}
    for index, row in enumerate(rows):
        housing_type, quote = text(row.get("sourceHousingTypeName")), quotes.get(f"supplyRows[{index}]")
        # 공급대상 괄호는 병합 셀이라 표 한 줄 인용에 없을 수 있어 괄호 앞 표기만 대조한다(화면과 같은 기준).
        core = WHITESPACE.sub("", housing_type).split("(")[0]
        if housing_type in lh_types and quote and core not in quote:
            warnings.append(f"supplyRows[{index}].sourceHousingTypeName: 근거 인용에 없는 LH 주택형 이름({housing_type})")


def remap_pages(items: list[dict], pages: list[int], warnings: list[str]) -> list[dict]:
    """원본 쪽 번호로 바꾼 항목을 돌려준다. 범위를 벗어난 항목은 그대로 두고 경고한다."""
    remapped = []
    for item in items:
        page = item.get("page")
        if not isinstance(page, int) or isinstance(page, bool) or not 1 <= page <= len(pages):
            warnings.append(f"{label(item)}: 첨부한 쪽 범위를 벗어난 근거 쪽 번호({page})")
            continue
        item["page"] = pages[page - 1]
        remapped.append(item)
    return remapped


def check_quotes(evidence: list[dict], texts: list[str], warnings: list[str]) -> None:
    """근거 인용문의 낱말이 모두 그 쪽 원문에 있는지 본다. 없으면 값이 다른 쪽에서 왔거나 지어낸 것일 수 있다.
    모델은 표 한 줄을 칸 순서대로 이어 인용하는데 PDF 텍스트는 칸을 다른 순서로 뽑기도 해서, 인용문 전체가 아니라 낱말마다 찾는다."""
    compacts = [WHITESPACE.sub("", page) for page in texts]
    for item in evidence:
        page = item["page"]
        missing = [word for word in text(item.get("quote")).split() if word not in compacts[page - 1]]
        if missing:
            warnings.append(f"{label(item)}: 근거 인용문의 {', '.join(missing[:3])}이(가) {page}쪽 원문에 없음")


def check_lh_counts(rows: list[dict], lh_types: list[str], lh_counts: list[int | None], warnings: list[str]) -> None:
    """공급행은 lhSourceRow(LH API 행의 1부터 센 번호)로 LH 행을 가리킨다. 통합된 LH 행 하나가 PDF에서 주택형별로 나뉘면
    여러 공급행이 같은 번호를 쓴다. 번호가 없거나 범위 밖인 행, 공급행이 없는 LH 행, LH 모집수와 다른 PDF 모집수(나뉜 행은 합계)를
    경고로 알린다. 모집수 차이는 PDF와 API 중 하나를 확인할 일이지 PDF 값을 못 쓰는 것은 아니다."""
    if not lh_counts:
        return
    groups: dict[int, list[int]] = {}
    for index, row in enumerate(rows):
        number = row.get("lhSourceRow")
        if not isinstance(number, int) or isinstance(number, bool) or not 1 <= number <= len(lh_counts):
            warnings.append(f"supplyRows[{index}].lhSourceRow: LH API 공급행 번호가 없거나 범위를 벗어남({number})")
            continue
        groups.setdefault(number, []).append(index)
    for number, (type_name, expected) in enumerate(zip(lh_types, lh_counts, strict=True), start=1):
        indexes = groups.get(number)
        if not indexes:
            warnings.append(f"LH API 공급행 {number}({type_name}): 이 행에서 나온 공급행이 없음")
            continue
        counts = [rows[index].get("totalSupplyHouseholdCount") for index in indexes]
        # 행 모집수가 비어 있으면 공통 모집수(sharedCounts)로 묶인 행이라 맞대지 않는다. LH API는 모집수를 모르면 0을 보낸다.
        if not expected or not all(isinstance(count, int) and not isinstance(count, bool) for count in counts):
            continue
        total = sum(counts)
        if total == expected:
            continue
        if len(indexes) == 1:
            warnings.append(f"supplyRows[{indexes[0]}].totalSupplyHouseholdCount: LH API 공급행 {number} 모집수 {expected}와 다름({total})")
        else:
            paths = ", ".join(f"supplyRows[{index}]" for index in indexes)
            warnings.append(f"{paths}: 나뉜 공급행 모집수 합계 {total}이(가) LH API 공급행 {number} 모집수 {expected}와 다름")


def check_target_count(rows: list[dict], rental_rows: int, warnings: list[str]) -> None:
    """추론을 낮추면 모델이 한 공급계층(예: 기존거주자)을 모든 행에서 통째로 빠뜨린다. 그래도 값은 맞아 보여
    다른 검산에 걸리지 않으므로, 공급대상 수가 임대조건표 줄 수보다 적으면 경고한다."""
    targets = sum(len(objects(row.get("targets"))) for row in rows)
    if targets < rental_rows:
        warnings.append(f"supplyRows: 공급대상 {targets}개가 임대조건표 {rental_rows}줄보다 적어 빠진 대상이 있을 수 있음")


def check_announcement(announcement: dict, warnings: list[str]) -> None:
    for field in DATE_FIELDS:
        value = text(announcement.get(field))
        if value and not DATE.fullmatch(value):
            warnings.append(f"announcement.{field}: 날짜 형식이 아님({value})")


def check_complexes(announcement: dict, complexes: list[dict], warnings: list[str]) -> None:
    title = text(announcement.get("name"))
    for index, complex_ in enumerate(complexes):
        if title and title == text(complex_.get("sourceComplexName")):
            warnings.append(f"complexes[{index}].sourceComplexName: 단지명이 공고 제목 전체와 같음")


def check_supply_row(row: dict, path: str, posted_date: str, lh_types: set[str], warnings: list[str]) -> None:
    housing_type = text(row.get("sourceHousingTypeName"))
    if housing_type and AREA_ONLY.fullmatch(housing_type) and housing_type not in lh_types:
        warnings.append(f"{path}.sourceHousingTypeName: 면적 숫자만 있음({housing_type})")
    if row.get("commonArea") is not None and row.get("supplyArea") is not None:
        warnings.append(f"{path}.supplyArea: 공용면적이 있는데 공급면적도 채워짐")
    move_in = text(row.get("expectedMoveInMonth"))
    if move_in and len(posted_date) >= 7 and move_in < posted_date[:7]:
        warnings.append(f"{path}.expectedMoveInMonth: 공고일보다 이른 연월({move_in})")
    for index, target in enumerate(objects(row.get("targets"))):
        name = text(target.get("target"))
        if TARGET_NOTATION.search(name):
            warnings.append(f"{path}.targets[{index}].target: 표기 규칙 위반({name})")


def label(item: dict) -> str:
    if "path" in item:
        return text(item.get("path"))
    return json.dumps(item.get("paths"), ensure_ascii=False, separators=(",", ":"))


def objects(value: object) -> list[dict]:
    """모델이 배열 자리에 다른 값을 넣어도 검사가 멈추지 않게, 객체 항목만 본다."""
    return [item for item in value if isinstance(item, dict)] if isinstance(value, list) else []


def text(value: object) -> str:
    return value if isinstance(value, str) else ""


# 관리자 화면(extraction.ts validPath)이 읽는 경로 형식. 하나라도 어긋나면 화면이 추출 결과 전체를 거부한다.
NOTE_PATH = re.compile(r"[A-Za-z][A-Za-z0-9]*(?:\[(?:\d+|\*)\]|\.[A-Za-z][A-Za-z0-9]*)*")


def normalize_note_paths(tree: dict, warnings: list[str]) -> None:
    """모델이 미확인·해당없음 경로를 complexes[].parkingCount처럼 쓰면 화면이 결과 전체를 읽지 못한다.
    빈 순번은 전체([*])로 바꾼다. 그래도 읽을 수 없는 미확인 경로는 읽을 수 있는 앞부분까지 넓히고(범위가 넓어져도 보수적이다),
    해당없음 경로는 넓히면 그 아래 값이 모두 반영에서 빠지므로 빼고 경고로 남긴다."""
    for key, holder, widen in (("unresolvedFields", tree.get("data", {}), True), ("notApplicable", tree, False)):
        kept = []
        for item in objects(holder.get(key)):
            original = item.get("path")
            path = re.sub(r"^\$\.?|^data\.", "", original.strip()).replace("[]", "[*]") if isinstance(original, str) else ""
            usable = path if NOTE_PATH.fullmatch(path) else (readable_prefix(path) if widen else "")
            if not usable:
                warnings.append(f"{key}: 경로를 읽을 수 없는 항목을 뺐습니다({original}: {item.get('reason')})")
                continue
            if usable != path:
                warnings.append(f"{key}: 읽을 수 없는 경로 {original}를 {usable}로 넓혔습니다.")
            kept.append({**item, "path": usable})
        if key in holder or kept:
            holder[key] = kept


def readable_prefix(path: str) -> str:
    """경로 앞에서 온전한 마디(. 이나 [ 앞)까지만 남긴다."""
    match = NOTE_PATH.match(path)
    prefix = match.group() if match else ""
    if prefix and path[len(prefix):][:1] not in ".[":
        prefix = re.sub(r"(?:\.[A-Za-z0-9]*|\[[^\]]*)$", "", prefix)
    return prefix
