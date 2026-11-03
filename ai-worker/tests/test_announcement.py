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
from test_pages import COVER, NOTICE, RENT, RENT_CELLS, SUPPLY, pdf

USAGE = {"promptTokens": 9000, "outputTokens": 3000, "thinkingTokens": 500}
EXTRACTION = {
    "data": {"announcement": {"name": "여수미평1 영구임대주택 예비입주자 모집"}, "complexes": [],
             "supplyRows": [{"sourceHousingTypeName": "16A", "lhSourceRow": 1, "totalSupplyHouseholdCount": 78}], "schedules": []},
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
               attempts=1, lease_token=uuid4())


def test_result_has_contract_keys_and_original_page_numbers():
    model = FakeModel()

    result = AnnouncementPdfExtractor(model).extract(job(pdf(COVER, NOTICE, SUPPLY, RENT, NOTICE)))

    assert result.keys() == contract_example("result.example.json").keys()
    assert (result["pages"], result["totalPages"], result["usage"]) == ([1, 3, 4, 5], 5, USAGE)
    tree = json.loads(result["json"])
    # LH 행 없이 모델이 쓴 LH 행 번호는 가리킬 LH 행이 없어 비운다.
    assert tree["data"]["supplyRows"] == [dict(EXTRACTION["data"]["supplyRows"][0], lhSourceRow=None)]
    assert {key: value for key, value in tree["data"].items() if key != "supplyRows"} == {
        key: value for key, value in EXTRACTION["data"].items() if key != "supplyRows"}
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
    assert "[LH 공급 API 행]\n1. 주택형=16A(대학생/청년) | 전용=16.37 | 공급=27.6384 | 모집수=78\n\n[첨부 1쪽]" in prompt
    assert result["warnings"] == []


def test_lh_count_mismatch_is_warning_not_failure():
    job_input = {"lhSupplyRows": [{"typeName": "16A", "exclusiveArea": "16.37", "supplyArea": "27.6", "suppliedUnitCount": "80"}]}

    result = AnnouncementPdfExtractor(FakeModel()).extract(job(pdf(COVER, SUPPLY, RENT), job_input))

    # 가짜 공급 표에는 "16A"가 없어, LH 이름을 옮긴 경우로도 경고한다.
    assert result["warnings"] == ["supplyRows[0].sourceHousingTypeName: 근거 인용에 없는 LH 주택형 이름(16A)",
                                  "supplyRows[0].totalSupplyHouseholdCount: LH API 공급행 1 모집수 80와 다름(78)"]


def split_row(housing_type, count, constructed, lh_row=1, subtypes=()):
    return {"complexReference": {"sourceComplexName": "대구테크노폴리스4"}, "sourceHousingTypeName": housing_type,
            "lhSourceRow": lh_row, "totalSupplyHouseholdCount": count, "constructedHouseholdCount": constructed,
            "subtypes": list(subtypes), "targets": [{"target": "일반", "supplyHouseholdCount": None}]}


MERGED = "LH:P747:SUPPLY:0"
MERGED_LH_INPUT = {"lhSupplyRows": [{"order": 0, "rowIdentifier": MERGED, "complexLabel": "대구테크노폴리스4", "typeName": "51", "exclusiveArea": "",
                                     "supplyArea": "", "suppliedUnitCount": "240", "totalUnitCount": "719"}]}


def test_merged_lh_row_split_by_pdf_housing_types_keeps_counts_and_source_row():
    # LH는 모집 240 한 행만 보내고, 공급 표는 주택형마다 이번 모집수(200·25·15)를 따로 적는다. 건설호수(468·183·68)는 모집수가 아니다.
    extraction = dict(EXTRACTION, data=dict(EXTRACTION["data"], supplyRows=[
        split_row("051.9800A", 200, 468), split_row("059.9100A", 25, 183), split_row("059.9300B", 15, 68)]), evidence=[])
    model = FakeModel(json.dumps(extraction, ensure_ascii=False))

    result = AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT), MERGED_LH_INPUT))

    assert "[LH 공급 API 행]\n1. 주택형=51 | 전용= | 공급= | 모집수=240\n\n" in model.prompts[0]
    rows = json.loads(result["json"])["data"]["supplyRows"]
    assert [(row["sourceHousingTypeName"], row["lhSourceRow"], row["totalSupplyHouseholdCount"]) for row in rows] == [
        ("051.9800A", MERGED, 200), ("059.9100A", MERGED, 25), ("059.9300B", MERGED, 15)]
    assert result["warnings"] == []


def test_split_rows_not_adding_up_to_lh_count_are_warned_without_changing_values():
    extraction = dict(EXTRACTION, data=dict(EXTRACTION["data"], supplyRows=[
        split_row("051.9800A", 200, 468), split_row("059.9100A", 25, 183)]), evidence=[])

    result = AnnouncementPdfExtractor(FakeModel(json.dumps(extraction, ensure_ascii=False))).extract(
        job(pdf(COVER, SUPPLY, RENT), MERGED_LH_INPUT))

    assert [row["totalSupplyHouseholdCount"] for row in json.loads(result["json"])["data"]["supplyRows"]] == [200, 25]
    assert result["warnings"] == ["supplyRows[0], supplyRows[1]: 나뉜 공급행 모집수 합계 225이(가) LH API 공급행 1 모집수 240와 다름"]


def test_lh_source_row_number_is_returned_as_that_rows_identifier():
    # 원천 순번이 1부터 시작하지 않아도(0번 행이 빠짐) 모델이 쓴 목록 번호를 그 행의 식별자로 돌려준다. 범위 밖 번호는 비운다.
    job_input = {"lhSupplyRows": [
        {"order": 1, "rowIdentifier": "LH:P:SUPPLY:1", "typeName": "51", "exclusiveArea": "", "supplyArea": "", "suppliedUnitCount": "200"},
        {"order": 3, "rowIdentifier": "LH:P:SUPPLY:3", "typeName": "59", "exclusiveArea": "", "supplyArea": "", "suppliedUnitCount": "40"}]}
    extraction = dict(EXTRACTION, data=dict(EXTRACTION["data"], supplyRows=[
        split_row("059.9100A", 25, 183, lh_row=2), split_row("051.9800A", 200, 468, lh_row=1), split_row("059.9300B", 15, 68, lh_row=3)]),
        evidence=[])

    result = AnnouncementPdfExtractor(FakeModel(json.dumps(extraction, ensure_ascii=False))).extract(
        job(pdf(COVER, SUPPLY, RENT), job_input))

    rows = json.loads(result["json"])["data"]["supplyRows"]
    assert [row["lhSourceRow"] for row in rows] == ["LH:P:SUPPLY:3", "LH:P:SUPPLY:1", None]
    assert "supplyRows[2].lhSourceRow: LH API 공급행 번호가 없거나 범위를 벗어남(3)" in result["warnings"]


def test_shared_count_for_several_types_stays_one_row_without_warning():
    # 공급 표가 두 주택형을 모집수 하나로만 안내하면 한 공급행에 subtypes로 두고 수를 나누거나 복제하지 않는다.
    subtypes = [{"name": "051.9800A", "exclusiveArea": 51.98, "commonArea": None, "constructedHouseholdCount": 468},
                {"name": "059.9100A", "exclusiveArea": 59.91, "commonArea": None, "constructedHouseholdCount": 183}]
    extraction = dict(EXTRACTION, data=dict(EXTRACTION["data"], supplyRows=[split_row("051.9800A/059.9100A", 240, None, subtypes=subtypes)]),
                      evidence=[])

    result = AnnouncementPdfExtractor(FakeModel(json.dumps(extraction, ensure_ascii=False))).extract(
        job(pdf(COVER, SUPPLY, RENT), MERGED_LH_INPUT))

    [row] = json.loads(result["json"])["data"]["supplyRows"]
    assert (row["lhSourceRow"], row["totalSupplyHouseholdCount"], len(row["subtypes"])) == (MERGED, 240, 2)
    assert result["warnings"] == []


def test_without_lh_rows_frame_is_not_sent():
    model = FakeModel()

    AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT), {"lhSupplyRows": []}))

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
        LhSupplyRow("16A(대학생/청년)", "16.37", "27.6384", "78", "LH:2015122300018207:SUPPLY:0"),
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


def test_missing_targets_against_rent_table_is_warning_not_failure(monkeypatch):
    table = SimpleNamespace(to_markdown=lambda: "|표|", extract=lambda: RENT_CELLS)
    monkeypatch.setattr(pymupdf.Page, "find_tables", lambda page: SimpleNamespace(tables=[table]))

    result = AnnouncementPdfExtractor(FakeModel()).extract(job(pdf(COVER, SUPPLY, RENT)))

    assert "supplyRows: 공급대상 0개가 임대조건표 4줄보다 적어 빠진 대상이 있을 수 있음" in result["warnings"]


def test_prompt_order_matches_evaluation():
    parts = [announcement.TEXT_PREFACE, announcement.INSTRUCTIONS, announcement.SCHEMA_SUFFIX, announcement.SCHEMA]
    rows = [LhSupplyRow("39", "39.95", "55.10", "20", "LH:P:SUPPLY:0"), LhSupplyRow("51A", "51.84", "71.0", "0", "LH:P:SUPPLY:1")]

    assert announcement.build_prompt([], "[첨부 1쪽]\n쪽") == "".join(parts) + "\n\n[첨부 1쪽]\n쪽"
    assert announcement.build_prompt(rows, "[첨부 1쪽]\n쪽") == (
        parts[0] + parts[1] + announcement.LH_FRAME + parts[2] + parts[3] + "\n\n[LH 공급 API 행]\n"
        "1. 주택형=39 | 전용=39.95 | 공급=55.10 | 모집수=20\n"
        "2. 주택형=51A | 전용=51.84 | 공급=71.0 | 모집수=0\n\n[첨부 1쪽]\n쪽"
    )
