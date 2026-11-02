import json
import time
from types import SimpleNamespace
from uuid import uuid4

import pymupdf

import pytest

from ai_worker import announcement
from ai_worker.announcement import AnnouncementPdfExtractor, LhSupplyRow
from ai_worker.deepseek import Reply
from ai_worker import deepseek
from ai_worker.extraction import ATTEMPT_BUDGET, PermanentError
from ai_worker.jobs import Job
from conftest import contract_example
from test_pages import COVER, NOTICE, RENT, SUPPLY, pdf

USAGE = {"promptTokens": 9000, "outputTokens": 3000, "thinkingTokens": 500}
EXTRACTION = {
    "data": {"announcement": {"name": "여수미평1 영구임대주택 예비입주자 모집"}, "complexes": [],
             "supplyRows": [{"sourceHousingTypeName": "16A", "totalSupplyHouseholdCount": 78}]},
    "sharedCounts": [], "notApplicable": [],
    "evidence": [{"path": "supplyRows[0]", "page": 2, "quote": "공용 26.37 298"}],
}


class FakeModel:
    def __init__(self, content=json.dumps(EXTRACTION, ensure_ascii=False) + "\n뒤에 붙은 글"):
        self.content = content
        self.prompts = []
        self.deadlines = []

    def chat(self, prompt, deadline):
        self.prompts.append(prompt)
        self.deadlines.append(deadline)
        return Reply(self.content, USAGE)


def job(content: bytes, job_input=None, kind=announcement.KIND) -> Job:
    return Job(id=31, kind=kind, announcement_id=1, file_name="notice.pdf", pdf=content, input=job_input,
               attempts=1, max_attempts=3, lease_token=uuid4())


def test_result_has_contract_keys_and_original_page_numbers():
    model = FakeModel()

    result = AnnouncementPdfExtractor(model).extract(job(pdf(COVER, NOTICE, SUPPLY, RENT, NOTICE)))

    assert result.keys() == contract_example("result.example.json").keys()
    assert (result["pages"], result["totalPages"], result["usage"]) == ([1, 3, 4, 5], 5, USAGE)
    tree = json.loads(result["json"])
    assert tree["data"] == EXTRACTION["data"]
    assert tree["evidence"][0]["page"] == 3
    assert result["warnings"] == []
    [prompt] = model.prompts
    assert prompt.startswith(announcement.TEXT_PREFACE + announcement.INSTRUCTIONS)
    assert "[첨부 2쪽]\n" + SUPPLY in prompt
    assert NOTICE not in prompt.split("[첨부 1쪽]")[1].split("[첨부 2쪽]")[0]


def test_lh_rows_are_sent_as_supply_row_frame_and_counts_are_checked():
    model = FakeModel()
    job_input = contract_example("input.example.json")

    result = AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT), job_input))

    [prompt] = model.prompts
    assert announcement.LH_FRAME in prompt
    assert "[LH 공급 API 행]\n0. 주택형=16A(대학생/청년) | 전용=16.37 | 공급=27.6384 | 모집수=78\n\n[첨부 1쪽]" in prompt
    assert result["warnings"] == []


def test_lh_count_mismatch_is_warning_not_failure():
    job_input = {"lhSupplyRows": [{"typeName": "16A", "exclusiveArea": "16.37", "supplyArea": "27.6", "suppliedUnitCount": "80"}],
                 "housingTypes": []}

    result = AnnouncementPdfExtractor(FakeModel()).extract(job(pdf(COVER, SUPPLY, RENT), job_input))

    assert result["warnings"] == ["supplyRows[0].totalSupplyHouseholdCount: LH API 모집수 80와 다름(78)"]


def test_without_lh_rows_frame_is_not_sent():
    model = FakeModel()

    AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT), {"lhSupplyRows": [], "housingTypes": []}))

    assert announcement.LH_FRAME not in model.prompts[0]
    assert "[LH 공급 API 행]" not in model.prompts[0]


def test_whole_document_is_sent_with_warning_when_tables_are_not_found():
    result = AnnouncementPdfExtractor(FakeModel()).extract(job(pdf(COVER, NOTICE, NOTICE)))

    assert result["pages"] == [1, 2, 3]
    assert result["warnings"][0] == "공급표·임대조건표 쪽을 찾지 못해 전체 3쪽을 보냈습니다."


def test_unreadable_pdf_fails_before_paid_call():
    model = FakeModel()

    with pytest.raises(PermanentError):
        AnnouncementPdfExtractor(model).extract(job(b"<html>not a pdf</html>"))

    assert model.prompts == []


def test_other_job_kind_is_not_extracted():
    model = FakeModel()

    with pytest.raises(PermanentError):
        AnnouncementPdfExtractor(model).extract(job(pdf(COVER), kind="OTHER"))

    assert model.prompts == []


def test_input_example_parses_as_lh_rows():
    assert announcement.lh_rows(contract_example("input.example.json")) == [
        LhSupplyRow("16A(대학생/청년)", "16.37", "27.6384", "78"),
    ]


def test_model_deadline_counts_from_attempt_start():
    model = FakeModel()
    started = time.monotonic()

    AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT)))

    [deadline] = model.deadlines
    budget = ATTEMPT_BUDGET.total_seconds() - deepseek.READ_TIMEOUT
    assert started + budget - 5 < deadline <= time.monotonic() + budget


def test_slow_pdf_fails_before_paid_call(monkeypatch):
    monkeypatch.setattr(announcement, "MIN_MODEL_TIME", ATTEMPT_BUDGET.total_seconds() + 1)
    model = FakeModel()

    with pytest.raises(PermanentError) as error:
        AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT)))

    assert error.value.code == "PDF_TOO_SLOW"
    assert model.prompts == []


def test_table_conversion_failure_sends_page_text_only(monkeypatch):
    class BrokenTable:
        def to_markdown(self):
            raise ValueError("table")

    monkeypatch.setattr(pymupdf.Page, "find_tables", lambda page: SimpleNamespace(tables=[BrokenTable()]))
    model = FakeModel()

    result = AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT)))

    assert result["pages"] == [1, 2, 3]
    assert "[첨부 2쪽]\n" + SUPPLY in model.prompts[0]
    assert "[표 1]" not in model.prompts[0]


def test_prompt_order_matches_evaluation():
    parts = [announcement.TEXT_PREFACE, announcement.INSTRUCTIONS, announcement.SCHEMA_SUFFIX, announcement.SCHEMA]
    rows = [LhSupplyRow("39", "39.95", "55.10", "20"), LhSupplyRow("51A", "51.84", "71.0", "0")]

    assert announcement.build_prompt([], "[첨부 1쪽]\n쪽") == "".join(parts) + "\n\n[첨부 1쪽]\n쪽"
    assert announcement.build_prompt(rows, "[첨부 1쪽]\n쪽") == (
        parts[0] + parts[1] + announcement.LH_FRAME + parts[2] + parts[3] + "\n\n[LH 공급 API 행]\n"
        "0. 주택형=39 | 전용=39.95 | 공급=55.10 | 모집수=20\n"
        "1. 주택형=51A | 전용=51.84 | 공급=71.0 | 모집수=0\n\n[첨부 1쪽]\n쪽"
    )
