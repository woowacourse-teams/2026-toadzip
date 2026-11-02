"""모델이 돌려준 추출 JSON을 관리 화면에 넘기기 전에 다듬는다.

모델은 첨부한 쪽 기준 번호를 쓰므로 원본 쪽 번호로 바꾼다. 자주 어기는 규칙과 검산 불일치는 값을 고치지 않고
경고로만 남겨 관리자가 보정 화면에서 확인하게 한다. 행 모집수와 대상별 합계처럼 화면이 이미 검산하는 것은 여기서 다시 하지 않는다.
"""

import json
import re

from ai_worker.extraction import MODEL_RESPONSE_INVALID, PermanentError

INVALID_CODE = MODEL_RESPONSE_INVALID
DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
AREA_ONLY = re.compile(r"[\d.]+")
TARGET_NOTATION = re.compile(r"「|\s\(")
WHITESPACE = re.compile(r"\s+")
DATE_FIELDS = ("postedDate", "applicationStartDate", "applicationEndDate", "winnerAnnouncementDate")


def first_json_object(reply: str) -> dict:
    """응답 뒤에 글이 더 붙는 경우가 있어(평가의 Extra data 실패) 첫 JSON 객체만 읽는다.
    NaN·Infinity는 JSON이 아니어서 화면이 결과를 읽지 못하므로 받지 않는다."""
    start = reply.find("{")
    if start < 0:
        raise PermanentError(INVALID_CODE, "AI 모델 응답에서 JSON을 찾지 못했습니다.")
    try:
        tree, _ = json.JSONDecoder(parse_constant=reject_constant).raw_decode(reply, start)
    except (json.JSONDecodeError, ValueError) as error:
        raise PermanentError(INVALID_CODE, "AI 모델 응답을 JSON으로 읽지 못했습니다.") from error
    if not isinstance(tree.get("data"), dict):
        raise PermanentError(INVALID_CODE, "AI 모델 응답에 추출 결과(data)가 없습니다.")
    return tree


def reject_constant(name: str):
    raise ValueError(f"JSON이 아닌 값: {name}")


def review(tree: dict, pages: list[int], texts: list[str], lh_types: list[str], lh_counts: list[int | None]) -> list[str]:
    """tree의 쪽 번호를 원본 기준으로 바꾸고 경고를 돌려준다.
    texts는 원본 PDF 전체 쪽 텍스트, lh_types·lh_counts는 공급행 틀로 준 LH API 행의 주택형과 모집수다."""
    warnings: list[str] = []
    evidence = remap_pages(objects(tree.get("evidence")), pages, warnings)
    remap_pages(objects(tree.get("sharedCounts")), pages, warnings)
    check_quotes(evidence, texts, warnings)
    data = tree["data"]
    announcement = data.get("announcement") if isinstance(data.get("announcement"), dict) else {}
    check_announcement(announcement, warnings)
    check_complexes(announcement, objects(data.get("complexes")), warnings)
    rows = objects(data.get("supplyRows"))
    for index, row in enumerate(rows):
        check_supply_row(row, f"supplyRows[{index}]", text(announcement.get("postedDate")), set(lh_types), warnings)
    check_lh_counts(rows, lh_counts, warnings)
    return warnings


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


def check_lh_counts(rows: list[dict], lh_counts: list[int | None], warnings: list[str]) -> None:
    """LH 공고는 LH API 행을 공급행 틀로 주므로 행 수와 행 모집수가 API와 같아야 한다. 다르면 PDF와 API 중 하나를 확인해야 한다."""
    if not lh_counts:
        return
    if len(rows) != len(lh_counts):
        warnings.append(f"supplyRows: LH API 공급행 {len(lh_counts)}개와 개수가 다름({len(rows)}개)")
        return
    for index, (row, expected) in enumerate(zip(rows, lh_counts)):
        count = row.get("totalSupplyHouseholdCount")
        # 행 모집수가 비어 있으면 공통 모집수(sharedCounts)로 묶인 행이라 행 단위로 맞대지 않는다.
        # LH API는 모집수를 모르면 0을 보낸다.
        if expected and isinstance(count, int) and count != expected:
            warnings.append(f"supplyRows[{index}].totalSupplyHouseholdCount: LH API 모집수 {expected}와 다름({count})")


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
    # LH API 주택형은 "39"처럼 숫자만인 경우가 많고, 프롬프트가 그 이름을 그대로 쓰라고 한다.
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
