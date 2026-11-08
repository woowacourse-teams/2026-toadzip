"""공고문 PDF 추출기. 고른 쪽의 텍스트와 표를 OpenAI Luna에 보내고, 결과를 PDF 보정 화면이 읽는 형식(result.example.json)으로 만든다.

이 모듈은 실행 흐름만 맡는다. 쪽 선택은 pages, 공급표를 코드와 모델 중 어디서 읽을지는 supply_sources,
본문 단계 프롬프트는 stage_prompts, 두 단계 결과 합치기는 merge가 한다.
LH 공고는 이미 수집한 LH 행을 참고 목록·검산에, GH 공고는 공급행 틀을 식별자 맞추기에 쓴다(source_frame). 모델·문구·쪽 입력을 바꾸면 대표 PDF로 정확도를 다시 확인한다.
"""

import json
import logging
import time
from concurrent.futures import ThreadPoolExecutor

from ai_worker import checks, merge, openai, pages, source_frame, stage_prompts, supply_sources
from ai_worker.extraction import ATTEMPT_BUDGET, PermanentError
from ai_worker.jobs import Job

log = logging.getLogger(__name__)

KIND = "ANNOUNCEMENT_PDF_EXTRACTION"
SUPPLY_TABLE_UNSTRUCTURED = "SUPPLY_TABLE_UNSTRUCTURED"
PDF_TEXT_MISSING = "PDF_TEXT_MISSING"
# 쪽 처리가 이만큼도 남기지 않으면 모델을 부르지 않는다. 쪽마다 확인해 남은 쪽을 읽지 않고 바로 실패로 끝낸다.
# 모델 호출과 응답 뒤 검산을 위한 최소 시간 여유다.
MIN_MODEL_TIME = 240


class AnnouncementPdfExtractor:
    def __init__(self, model: supply_sources.Model, column_judge: supply_sources.ColumnJudge | None = None):
        self.model = model
        # 없으면 코드가 읽은 수량 열의 뜻을 확인할 수 없어 공급표는 늘 모델 단계로 처리한다.
        self.column_judge = column_judge

    def extract(self, job: Job) -> dict:
        if job.kind != KIND:
            raise PermanentError("UNSUPPORTED_JOB", "이 워커가 처리하지 않는 작업입니다.")
        # 시도 예산은 PDF 처리부터 잰다. 모델 호출만 재면 큰 PDF의 쪽 처리 시간만큼 마감을 넘는다.
        deadline = time.monotonic() + ATTEMPT_BUDGET.total_seconds()
        frame = source_frame.SourceFrame.read(job.input)
        rows = frame.lh_rows
        model_cutoff = deadline - MIN_MODEL_TIME
        document = pages.open_pdf(job.pdf)
        with document:
            texts = pages.page_texts(document, model_cutoff)
            # 스캔본은 쪽마다 글자가 없어 표 칸을 읽을 수 없고, 값을 원문 칸과 대조할 수도 없다. 비용을 쓰기 전에 끝낸다.
            if not any(text.strip() for text in texts):
                raise PermanentError(
                    PDF_TEXT_MISSING,
                    "이미지로 된(스캔) PDF라 글자를 읽을 수 없어 자동 추출을 하지 않았습니다. "
                    "공급정보 편집에서 직접 입력하거나 글자가 들어 있는 PDF로 다시 시도해 주세요.",
                )
            selection = pages.select(texts)
            selected = pages.prompt_pages(document, selection.pages, model_cutoff)
        if selected.unstructured_supply_pages:
            numbers = ", ".join(str(number) for number in selected.unstructured_supply_pages)
            raise PermanentError(
                SUPPLY_TABLE_UNSTRUCTURED,
                f"공급·임대 표 구조를 읽지 못해 추출을 중단했습니다. 원본 PDF {numbers}쪽을 확인해 주세요.",
            )
        notice_prompt = stage_prompts.notice_prompt(selected)
        plan = supply_sources.SupplyPlan(self.model, self.column_judge, rows, selected, texts[:2])
        # 두 역할은 서로 의존하지 않는다. 같은 남은 마감 안에서 병렬 호출해 분리 비용이 순차 지연으로 누적되지 않게 한다.
        model_deadline = deadline - openai.READ_TIMEOUT
        with ThreadPoolExecutor(max_workers=2, thread_name_prefix="announcement-extraction") as pool:
            # 본문 단계는 공급표와 상관없이 늘 부르므로 먼저 보내고, 그동안 공급표를 읽을 곳을 정한다.
            notice_future = pool.submit(self.model.chat, notice_prompt, model_deadline)
            source = plan.start(model_cutoff, model_deadline, pool)
            source.wait()
            notice_reply = notice_future.result()
        supply = source.read()
        notice_tree = checks.first_json_object(notice_reply.content, {
            "announcement": dict, "receptionPlace": dict, "complexes": list, "schedules": list,
            "unresolvedFields": list,
        })
        warnings = [f"공급표·임대조건표 쪽을 찾지 못해 전체 {len(texts)}쪽을 보냈습니다."] if selection.whole_document else []
        warnings += supply.warnings
        # 공급 근거는 원문 행 ID에서 원본 쪽을 복원하므로 첨부 쪽 번호 변환을 하지 않는다.
        warnings += _remap_stage_pages(notice_tree, selected.notice_pages)
        # merge는 페이지 변환 전에 tree를 만들므로, 최종 결과의 근거를 원본 PDF 쪽 번호로 변환한다.
        tree, merge_warnings = merge.merge(supply.tree, notice_tree)
        warnings += merge_warnings
        warnings += checks.review(tree, list(range(1, len(texts) + 1)), texts, rows, selected.rental_rows)
        warnings += frame.link(tree)
        checks.normalize_note_paths(tree, warnings)
        usage = _combined_usage(*supply.usage, notice_reply.usage)
        log.info("작업 %d 추출 완료 pages=%d/%d tableRows=%d frameRows=%d warnings=%d supply=%s usage=%s", job.id,
                 len(selection.pages), len(texts), len(selected.supply_row_ids), len(frame), len(warnings),
                 supply.kind, usage)
        return {
            "json": json.dumps(tree, ensure_ascii=False),
            "pages": selection.pages,
            "totalPages": len(texts),
            "warnings": warnings,
            "usage": usage,
        }


def _remap_stage_pages(tree: dict, source_pages: list[int]) -> list[str]:
    warnings: list[str] = []
    checks.remap_pages(checks.objects(tree.get("sharedCounts")), source_pages, warnings)
    checks.remap_pages(checks.objects(tree.get("evidence")), source_pages, warnings)
    return warnings


def _combined_usage(*usages: dict) -> dict:
    result = {}
    for key in ("promptTokens", "outputTokens", "thinkingTokens"):
        values = [usage.get(key) for usage in usages]
        result[key] = sum(values) if all(isinstance(value, int) and not isinstance(value, bool) for value in values) else None
    return result
