"""TypeSafe Jev에 공급표 수량 열이 무엇을 세는지 묻는다. 키와 응답 원문은 로그와 오류 문구에 남기지 않는다.

판단은 Jev가, 그 판단을 쓸지는 코드(announcement)가 정한다. 질문 문구·선택지·기준값은 이 모듈에만 둔다.
GH·LH 표본 18개 열(고칠 때 보지 않은 9개 포함)에서 확률 0.7 이상이면서 틀린 답이 없었다.
"""

import math
import re
from dataclasses import dataclass

import httpx

from ai_worker.pages import SupplyTable

BASE_URL = "https://api.typesafe.ai/v1"
MODEL = "jev-1.14.0"
TIMEOUT = 15
# 이 확률 이상인 판단만 쓴다. 아래면 그 표를 모델 공급 단계로 넘긴다.
THRESHOLD = 0.7
SAMPLE_ROWS = 6

QUESTION = "What does each number in the column `{path}` of `table` count?"
OPTIONS = {
    "new_reserve_this_notice": "How many reserve (waiting-list) applicants this notice newly recruits, for example a 예비자 count listed under a 모집 (recruitment) heading.",
    "winners_total_this_notice": "How many households this notice selects as winners in total, before any split into priority and general supply.",
    "winners_priority_share": "Only the part of this notice's winners allotted to priority supply (우선공급).",
    "winners_general_share": "Only the part of this notice's winners allotted to general supply (일반공급).",
    "existing_waitlist": "Reserve applicants already waiting from earlier notices, labeled as remaining or existing (잔여·기존). A count under a 모집 (recruitment) heading is what this notice recruits, not this.",
    "building_or_complex_size": "A property of the building or complex, such as households constructed per type or total households in the complex, not a recruitment count.",
}
WINNER_OPTIONS = ("winners_total_this_notice", "winners_priority_share", "winners_general_share")
# 추출 필드가 뜻해야 하는 판단
FIELD_MEANING = {
    "reserveCount": "new_reserve_this_notice",
    "supplyHouseholdCount": "winners_total_this_notice",
    "totalSupplyHouseholdCount": "winners_total_this_notice",
}
RESERVE_NOTICE = re.compile(r"예비입주자\s*(?:추가)?\s*모집\s*공고")


class JevError(Exception):
    """Jev 판단을 받지 못했다. 호출하는 쪽은 이를 '아니오'가 아니라 판단 없음으로 다룬다."""


@dataclass(frozen=True)
class Meaning:
    choice: str
    probability: float


class Jev:
    def __init__(self, api_key: str, base_url: str = BASE_URL, transport: httpx.BaseTransport | None = None):
        self.client = httpx.Client(base_url=base_url, headers={"Authorization": f"Bearer {api_key}"},
                                   timeout=TIMEOUT, transport=transport)

    def count_meanings(self, state: dict, headers: dict[str, str], reserve_only: bool,
                       timeout: float = TIMEOUT) -> dict[str, Meaning]:
        """headers는 질문 키 → 열 머리글 경로다. 예비자만 모집하는 공고면 당첨자 선택지는 정답일 수 없어 뺀다.
        timeout은 남은 작업 시간으로 줄여 넘긴다. httpx 제한은 단계마다 걸리므로 TIMEOUT 이하로만 둔다."""
        criteria = {name: text for name, text in OPTIONS.items() if not (reserve_only and name in WINNER_OPTIONS)}
        request = {
            "model": MODEL,
            "state": state,
            "questions": {key: {"type": "choice", "instructions": QUESTION.format(path=path), "criteria": criteria}
                          for key, path in headers.items()},
        }
        try:
            response = self.client.post("/systemone", json=request, timeout=min(timeout, TIMEOUT))
        except httpx.HTTPError as error:
            raise JevError("Jev에 연결하지 못했습니다.") from error
        if response.status_code != 200:
            raise JevError(f"Jev가 요청을 거절했습니다(HTTP {response.status_code}).")
        try:
            answers = response.json()["answers"]
            result = {}
            for key in headers:
                choice = answers[key]["choice"]
                probability = float(answers[key]["probabilities"][choice])
                if not math.isfinite(probability):
                    raise ValueError("probability is not finite")
                result[key] = Meaning(choice, probability)
        except (ValueError, KeyError, TypeError) as error:
            raise JevError("Jev 응답 형식을 읽지 못했습니다.") from error
        return result


def is_reserve_notice(first_pages: list[str]) -> bool:
    """코드가 아는 사실로 넘긴다: 앞 쪽에 '예비입주자 (추가)모집 공고'가 있으면 예비자만 모집하는 공고다."""
    return bool(RESERVE_NOTICE.search(re.sub(r"\s+", " ", " ".join(first_pages))))


def table_state(table: SupplyTable, header_count: int) -> tuple[dict, list[str]]:
    """사람이 열의 뜻을 판단할 때 보는 것만 둔다: 여러 줄 머리글 경로와 앞쪽 몇 행의 값."""
    columns = max(map(len, table.cells), default=0)
    paths = header_paths(table.cells, header_count, columns)
    names = [path if path and paths.count(path) == 1 else f"{path or 'column'} ({column + 1})"
             for column, path in enumerate(paths)]
    rows = [{names[column]: row[column] if column < len(row) else None
             for column in range(columns)}
            for row in table.cells[header_count:header_count + SAMPLE_ROWS]]
    return {"table": {"column_headers": paths, "rows": rows}}, paths


def header_paths(cells: list[list[str | None]], header_count: int, columns: int) -> list[str]:
    """여러 줄 머리글을 열마다 '상위 > 하위' 경로로 만든다. 표 인식기의 None은 병합 칸이다.
    같은 열 위쪽에 글자가 있으면 세로 병합(위와 같은 머리글), 없으면 가로 병합(왼쪽 칸의 머리글)으로 본다."""
    paths: list[list[str]] = [[] for _ in range(columns)]
    for index in range(header_count):
        row = cells[index]
        for column in range(columns):
            cell = row[column] if column < len(row) else None
            if cell is None:
                if any(column < len(cells[above]) and cells[above][column] is not None for above in range(index)):
                    continue
                cell = next((row[left] for left in range(column - 1, -1, -1)
                             if left < len(row) and row[left] is not None), None)
            text = re.sub(r"\s+", " ", cell or "").strip()
            if text and (not paths[column] or paths[column][-1] != text):
                paths[column].append(text)
    return [" > ".join(path) for path in paths]
