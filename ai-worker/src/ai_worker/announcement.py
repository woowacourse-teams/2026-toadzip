"""공고문 PDF 추출기. 고른 쪽의 텍스트와 표를 DeepSeek에 보내고, 결과를 PDF 보정 화면이 읽는 형식(result.example.json)으로 만든다.

평가(.local/pdf-extraction-eval, lh-frame 변형)와 같은 순서로 프롬프트를 만든다. 문구를 바꾸면 정확도를 다시 재야 한다.
"""

import json
import logging
import time
from dataclasses import dataclass
from importlib.resources import files
from typing import Protocol

from ai_worker import checks, deepseek, pages
from ai_worker.deepseek import Reply
from ai_worker.extraction import ATTEMPT_BUDGET, PermanentError
from ai_worker.jobs import Job

log = logging.getLogger(__name__)

KIND = "ANNOUNCEMENT_PDF_EXTRACTION"
# 쪽 처리가 이만큼도 남기지 않으면 모델을 부르지 않는다. 같은 PDF는 다시 해도 같으므로 바로 실패로 끝낸다.
# 실측 응답 66~132초에 여유를 두고 읽기 한 번(deepseek.READ_TIMEOUT)을 더한 값이다.
MIN_MODEL_TIME = 240
PROMPTS = files("ai_worker") / "prompts"


def prompt_text(name: str) -> str:
    return (PROMPTS / name).read_text(encoding="utf-8")


INSTRUCTIONS = prompt_text("announcement-pdf-extraction.txt")
SCHEMA = prompt_text("announcement-pdf-extraction-schema.json")
TEXT_PREFACE = prompt_text("text-preface.txt")
SCHEMA_SUFFIX = prompt_text("schema-suffix.txt")
LH_FRAME = prompt_text("lh-frame.txt")


class Model(Protocol):
    def chat(self, content: str, deadline: float) -> Reply: ...


@dataclass(frozen=True)
class LhSupplyRow:
    """Spring이 input.lhSupplyRows에 담은 LH 공급 API 행. 값은 LH가 보낸 문자열 그대로다."""

    type_name: str
    exclusive_area: str
    supply_area: str
    supplied_unit_count: str

    @classmethod
    def of(cls, row: dict) -> "LhSupplyRow":
        return cls(row["typeName"], row["exclusiveArea"], row["supplyArea"], row["suppliedUnitCount"])

    def count(self) -> int | None:
        try:
            return int(self.supplied_unit_count)
        except (TypeError, ValueError):
            return None


def lh_rows(job_input: dict | None) -> list[LhSupplyRow]:
    return [LhSupplyRow.of(row) for row in (job_input or {}).get("lhSupplyRows", [])]


def build_prompt(rows: list[LhSupplyRow], page_text: str) -> str:
    """LH 공고면 이미 수집한 LH API 행을 공급행 틀로 함께 준다. 행이 없으면(LH가 아닌 공고) 틀 지시도 빼고 보낸다."""
    if not rows:
        return TEXT_PREFACE + INSTRUCTIONS + SCHEMA_SUFFIX + SCHEMA + "\n\n" + page_text
    frame = "\n".join(
        f"{index}. 주택형={row.type_name} | 전용={row.exclusive_area} | 공급={row.supply_area} | 모집수={row.supplied_unit_count}"
        for index, row in enumerate(rows)
    )
    return (TEXT_PREFACE + INSTRUCTIONS + LH_FRAME + SCHEMA_SUFFIX + SCHEMA + "\n\n"
            + "[LH 공급 API 행]\n" + frame + "\n\n" + page_text)


class AnnouncementPdfExtractor:
    def __init__(self, model: Model):
        self.model = model

    def extract(self, job: Job) -> dict:
        if job.kind != KIND:
            raise PermanentError("UNSUPPORTED_JOB", "이 워커가 처리하지 않는 작업입니다.")
        # 시도 예산은 PDF 처리부터 잰다. 모델 호출만 재면 큰 PDF의 쪽 처리 시간만큼 마감을 넘는다.
        deadline = time.monotonic() + ATTEMPT_BUDGET.total_seconds()
        rows = lh_rows(job.input)
        document = pages.open_pdf(job.pdf)
        with document:
            texts = pages.page_texts(document)
            selection = pages.select(texts)
            prompt = build_prompt(rows, pages.prompt_pages(document, selection.pages))
        if deadline - time.monotonic() < MIN_MODEL_TIME:
            raise PermanentError("PDF_TOO_SLOW", "PDF 쪽을 읽는 데 시간이 너무 오래 걸려 추출하지 못했습니다.")
        # 읽기 한 번(deepseek.READ_TIMEOUT)만큼 넘을 수 있어 그만큼 일찍 끝낸다.
        reply = self.model.chat(prompt, deadline - deepseek.READ_TIMEOUT)
        tree = checks.first_json_object(reply.content)
        warnings = []
        if selection.whole_document:
            warnings.append(f"공급표·임대조건표 쪽을 찾지 못해 전체 {len(texts)}쪽을 보냈습니다.")
        warnings += checks.review(tree, selection.pages, texts, [row.type_name for row in rows],
                                  [row.count() for row in rows])
        log.info("작업 %d 추출 완료 pages=%d/%d lhRows=%d warnings=%d usage=%s", job.id, len(selection.pages),
                 len(texts), len(rows), len(warnings), reply.usage)
        return {
            "json": json.dumps(tree, ensure_ascii=False),
            "pages": selection.pages,
            "totalPages": len(texts),
            "warnings": warnings,
            "usage": reply.usage,
        }
