"""공급표를 코드로 읽을지 모델로 읽을지 정한다. 어느 쪽이든 같은 모양의 공급 단계 결과(Supply)를 돌려준다.

공급표 구조를 코드가 확신하면(supply_grouping) 숫자를 셀에서 직접 읽고 공급 단계 모델 호출을 생략한다(CodeSupply).
정규식이 고른 수량 열의 뜻을 Jev가 확인하지 못하거나 LH 공고면 공급 단계를 모델에 맡긴다(ModelSupply).
"""

import logging
import time
from concurrent.futures import Executor
from dataclasses import dataclass, replace
from typing import Protocol

from ai_worker import checks, jev, pages, stage_prompts, supply_grouping
from ai_worker.lh import LhSupplyRow
from ai_worker.openai import Reply
from ai_worker.supply_assembly import assemble

log = logging.getLogger(__name__)


class Model(Protocol):
    def chat(self, content: str, deadline: float) -> Reply: ...


class ColumnJudge(Protocol):
    def count_meanings(self, state: dict, headers: dict[str, str], reserve_only: bool,
                       timeout: float = jev.TIMEOUT) -> dict[str, jev.Meaning]: ...


@dataclass(frozen=True)
class Supply:
    """공급 단계 결과. tree는 관리자 계약의 공급행·단지 부분이다."""

    tree: dict
    # 코드가 표를 읽으며 남긴 경고나 공급 단계에 보낸 입력에 대한 안내, 그다음 원문 대조 검산 경고
    warnings: list[str]
    usage: list[dict]
    # 로그에 남기는 공급표 처리 방식(code·model)
    kind: str


class CodeSupply:
    """코드가 표 셀에서 묶은 공급행. 모델을 부르지 않는다."""

    def __init__(self, grouped: supply_grouping.Grouped, selected: pages.PromptPages):
        self._grouped = grouped
        self._selected = replace(selected, supply_row_values=grouped.row_values)

    def wait(self) -> None:
        """기다릴 모델 호출이 없다."""

    def read(self) -> Supply:
        tree, source_warnings = assemble(self._grouped.compact, self._selected)
        return Supply(tree, [*self._grouped.warnings, *source_warnings], [], "code")


class ModelSupply:
    """공급 단계 모델 호출. 만들 때 호출을 보내 본문 단계 호출과 같은 마감 안에서 병렬로 돈다."""

    def __init__(self, model: Model, rows: list[LhSupplyRow], selected: pages.PromptPages, deadline: float,
                 pool: Executor):
        self._selected = selected
        self._future = pool.submit(model.chat, stage_prompts.supply_prompt(rows, selected), deadline)

    def wait(self) -> None:
        """호출 오류(일시 오류 포함)를 응답 형식 오류보다 먼저, 본문 단계 호출 오류보다도 먼저 알린다."""
        self._future.result()

    def read(self) -> Supply:
        reply = self._future.result()
        compact = checks.first_json_object(reply.content, {"rows": list})
        selected = self._selected
        # 코드가 공급표를 읽으면 공급 단계 호출이 없어 이 두 안내는 사실이 아니므로 모델 경로에만 둔다.
        warnings = []
        if selected.table_structure_fallback:
            warnings.append("표 셀 구조를 분리하지 못해 일부 쪽 텍스트를 공급 단계에도 전달했습니다.")
        if selected.ambiguous_supply_tables:
            warnings.append("공급 표인지 판단이 불확실한 표를 공급·본문 단계 양쪽에 전달했습니다.")
        tree, source_warnings = assemble(compact, selected)
        return Supply(tree, [*warnings, *source_warnings], [reply.usage], "model")


class SupplyPlan:
    """공급표를 읽을 곳을 정한다. 만들 때 외부 호출 없이 코드로 행을 묶어 두고(본문 단계 호출 전),
    start에서 Jev로 수량 열의 뜻을 확인해 CodeSupply나 ModelSupply를 돌려준다.
    first_pages는 공고 앞 쪽 원문이다(예비자만 모집하는 공고인지 본다)."""

    def __init__(self, model: Model, column_judge: ColumnJudge | None, rows: list[LhSupplyRow],
                 selected: pages.PromptPages, first_pages: list[str]):
        self._model = model
        self._column_judge = column_judge
        self._rows = rows
        self._selected = selected
        self._first_pages = first_pages
        # LH 공고는 모델이 LH API 행 번호(lhSourceRow)로 공급행을 나누고 맞추므로 모델 단계를 그대로 쓴다.
        self._grouped = None if rows else supply_grouping.group(
            selected.supply_tables, selected.supply_row_values, selected.tableless_supply_pages)

    def start(self, cutoff: float, deadline: float, pool: Executor) -> CodeSupply | ModelSupply:
        """본문 단계 호출을 보낸 뒤에 부른다. Jev 확인은 본문 단계 호출과 겹쳐 돈다.
        cutoff는 Jev에 물을 수 있는 마지막 시각, deadline은 모델 호출 마감이다."""
        if self._grouped and _counts_confirmed(self._column_judge, self._grouped, self._first_pages, cutoff):
            return CodeSupply(self._grouped, self._selected)
        return ModelSupply(self._model, self._rows, self._selected, deadline, pool)


def _counts_confirmed(column_judge: ColumnJudge | None, grouped: supply_grouping.Grouped, first_pages: list[str],
                      cutoff: float) -> bool:
    """정규식이 고른 수량 열의 뜻을 Jev가 같은 뜻으로, 기준 확률 이상으로 판단할 때만 코드 경로를 쓴다.
    Jev 호출 실패는 '다르다'가 아니라 판단 없음이라 모델 단계로 넘긴다.
    cutoff(time.monotonic 기준)가 지나면 묻지 않는다. 공급 단계 모델 호출에 쓸 시간을 남긴다."""
    if column_judge is None:
        log.info("Jev 키가 없어 수량 열의 뜻을 확인하지 못하므로 공급표를 모델 단계로 처리합니다.")
        return False
    if not grouped.count_columns:
        log.info("머리글 있는 수량표가 없어 수량 열의 뜻을 확인하지 못하므로 공급표를 모델 단계로 처리합니다.")
        return False
    reserve_only = jev.is_reserve_notice(first_pages)
    for table, header_count, fields in grouped.count_columns:
        if reserve_only and any(jev.FIELD_MEANING[name] in jev.WINNER_OPTIONS for name in fields):
            log.info("예비자만 모집하는 공고의 원본 %d쪽 수량표에 당첨자 열이 있어 공급표를 모델 단계로 처리합니다.",
                     table.page)
            return False
        remaining = cutoff - time.monotonic()
        if remaining <= 0:
            log.info("Jev에 물을 시간이 남지 않아 공급표를 모델 단계로 처리합니다.")
            return False
        state, paths = jev.table_state(table, header_count)
        try:
            meanings = column_judge.count_meanings(
                state, {f"column_{column}": paths[column] for column in fields.values()}, reserve_only,
                timeout=remaining)
        except jev.JevError as error:
            log.warning("%s 공급표를 모델 단계로 처리합니다.", error)
            return False
        for field_name, column in fields.items():
            meaning = meanings[f"column_{column}"]
            if meaning.choice != jev.FIELD_MEANING[field_name] or meaning.probability < jev.THRESHOLD:
                log.info("원본 %d쪽 '%s' 열을 Jev가 %s(%.2f)로 판단해 공급표를 모델 단계로 처리합니다.",
                         table.page, paths[column], meaning.choice, meaning.probability)
                return False
    return True
