import json
import time
from importlib.resources import files
from types import SimpleNamespace
from uuid import uuid4

import pymupdf

import pytest

from ai_worker import announcement, jev, tables
from ai_worker.announcement import AnnouncementPdfExtractor
from ai_worker import openai
from ai_worker.extraction import ATTEMPT_BUDGET, PermanentError, TransientError
from ai_worker.jobs import Job
from ai_worker.openai import Reply
from conftest import (COVER, EXTRACTION, NOTICE, RENT, RENT_CELLS, SUPPLY, USAGE, FakeJudge, FakeModel, contract_example,
                      grid_page, text_pdf, two_quantity_tables_pdf)

LH_FRAME = (files("ai_worker") / "prompts" / "lh-frame.txt").read_text(encoding="utf-8")
def pdf(*texts: str) -> bytes:
    document = pymupdf.open()
    for text in texts:
        page = document.new_page()
        if text == SUPPLY:
            draw_grid(page, [
                ["단지명", "공급형", "공급대상", "모집호수", "주거전용", "주거공용"],
                ["Sample Complex", "16A", "청년", "78", "26.37", "13.50"],
                ["Sample Complex", "31A", "고령자", "20", "31.32", "12.60"],
            ])
        else:
            page.insert_text((20, 72), text, fontname="korea", fontsize=6)
    return document.tobytes()


def draw_grid(page: pymupdf.Page, rows: list[list[str]]) -> None:
    xs, ys = [20, 110, 200, 300, 390, 480, 575], [45, 75, 105, 135]
    for x in xs:
        page.draw_line((x, ys[0]), (x, ys[len(rows)]))
    for y in ys[:len(rows) + 1]:
        page.draw_line((xs[0], y), (xs[-1], y))
    for row_index, row in enumerate(rows):
        for column, value in enumerate(row):
            page.insert_text((xs[column] + 2, ys[row_index] + 18), value, fontname="korea", fontsize=6)


def stage_prompt(model, stage):
    marker = "[추출 단계: 공급 표]" if stage == "supply" else "[추출 단계: 공고 본문]"
    return next(prompt for prompt in model.prompts if marker in prompt)


def job(content: bytes, job_input=None, kind=announcement.KIND) -> Job:
    return Job(id=31, kind=kind, announcement_id=1, file_name="notice.pdf", pdf=content, input=job_input,
               attempts=1, lease_token=uuid4())


def test_result_has_contract_keys_and_original_page_numbers():
    model = FakeModel()

    result = AnnouncementPdfExtractor(model).extract(job(pdf(COVER, NOTICE, SUPPLY, RENT, NOTICE)))

    assert result.keys() == contract_example("result.example.json").keys()
    assert (result["pages"], result["totalPages"], result["usage"]) == (
        [1, 3, 4], 5, {key: value * 2 for key, value in USAGE.items()})
    tree = json.loads(result["json"])
    # 원천 행이 없는 공고는 sourceRow가 비어 있다.
    assert len(tree["data"]["supplyRows"]) == 1
    assert tree["data"]["supplyRows"][0]["sourceHousingTypeName"] is None
    assert tree["data"]["supplyRows"][0]["sourceRow"] is None
    assert tree["data"]["supplyRows"][0]["totalSupplyHouseholdCount"] is None
    assert tree["data"]["supplyRows"][0]["targets"] == []
    assert {key: value for key, value in tree["data"].items() if key not in ("supplyRows", "unresolvedFields")} == {
        key: value for key, value in EXTRACTION["data"].items() if key != "supplyRows"}
    # 인식된 원문 표 행은 남지만, 모델 값이 해당 열과 일치하지 않아 미확인으로 둔다.
    assert tree["evidence"] and tree["evidence"][0]["page"] == 3
    assert {item["path"] for item in tree["data"]["unresolvedFields"]} >= {
        "supplyRows[0].sourceHousingTypeName", "supplyRows[0].totalSupplyHouseholdCount",
    }
    supply_prompt = stage_prompt(model, "supply")
    notice_prompt = stage_prompt(model, "notice")
    assert "[첨부 1쪽 / 원본 3쪽]" in supply_prompt
    assert "[첨부 1쪽 / 원본 1쪽]" in notice_prompt
    assert "[추출 단계: 공급 표]" in supply_prompt
    assert "[추출 단계: 공고 본문]" in notice_prompt
    assert "[첨부 1쪽 / 원본 1쪽]" + NOTICE not in notice_prompt
    assert "공급행이나 표 안의 모집수·임대조건은 추출하지 마세요" in notice_prompt
    assert len(model.prompts) == 2
    assert any("같은 의미의 원문 표 열에서 확인되지 않음" in item["reason"]
               for item in tree["data"]["unresolvedFields"])


def test_lh_rows_are_sent_as_reference_list_and_do_not_fill_source_row():
    model = FakeModel()
    job_input = contract_example("input.example.json")

    result = AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT), job_input))

    prompt = stage_prompt(model, "supply")
    assert LH_FRAME in prompt
    assert ("[LH 공급 API 행]\n- 단지=인천부평(도시재생뉴딜) 행복주택 | 주택형=16A(대학생/청년) | 전용=16.37 | 공급=27.6384 "
            "| 모집수=78") in prompt
    assert "lhSourceRow" not in prompt
    assert [row["sourceRow"] for row in json.loads(result["json"])["data"]["supplyRows"]] == [None]
    assert any("LH API" in warning for warning in result["warnings"]) is False


def test_unverified_lh_count_is_cleared_before_comparison():
    job_input = {"sourceSupplyRows": [{"source": "LH", "typeName": "16A", "exclusiveArea": "16.37", "supplyArea": "27.6", "suppliedUnitCount": "80"}]}

    result = AnnouncementPdfExtractor(FakeModel()).extract(job(pdf(COVER, SUPPLY, RENT), job_input))

    # 구조화된 행 ID가 없으므로 이름과 수량을 추출 확정값으로 남기지 않는다.
    assert json.loads(result["json"])["data"]["supplyRows"][0]["totalSupplyHouseholdCount"] is None
    assert json.loads(result["json"])["data"]["supplyRows"][0]["sourceHousingTypeName"] is None
    assert "supplyRows[0].totalSupplyHouseholdCount" in {
        item["path"] for item in json.loads(result["json"])["data"]["unresolvedFields"]
    }
    assert not any("모집수 합계 80와 다름" in warning for warning in result["warnings"])


def split_row(housing_type, count, constructed, subtypes=()):
    return {"complexReference": {"sourceComplexName": "대구테크노폴리스4"}, "sourceHousingTypeName": housing_type,
            "totalSupplyHouseholdCount": count, "constructedHouseholdCount": constructed,
            "subtypes": list(subtypes), "targets": [{"target": "일반", "supplyHouseholdCount": None}]}


MERGED = "LH:P747:SUPPLY:0"
MERGED_LH_INPUT = {"sourceSupplyRows": [{"source": "LH", "order": 0, "rowIdentifier": MERGED, "complexLabel": "대구테크노폴리스4", "typeName": "51", "exclusiveArea": "",
                                     "supplyArea": "", "suppliedUnitCount": "240", "totalUnitCount": "719"}]}


def test_pdf_rows_stand_on_their_own_when_lh_merges_housing_types():
    # LH는 모집 240 한 행만 보내고, 공급 표는 주택형마다 행을 둔다. 공급행은 PDF가 정하고 LH 행 식별자를 채우지 않는다.
    extraction = dict(EXTRACTION, data=dict(EXTRACTION["data"], supplyRows=[
        split_row("051.9800A", 200, 468), split_row("059.9100A", 25, 183), split_row("059.9300B", 15, 68)]), evidence=[])
    model = FakeModel(json.dumps(extraction, ensure_ascii=False))

    result = AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT), MERGED_LH_INPUT))

    assert "[LH 공급 API 행]\n- 단지=대구테크노폴리스4 | 주택형=51 | 전용= | 공급= | 모집수=240" in stage_prompt(model, "supply")
    rows = json.loads(result["json"])["data"]["supplyRows"]
    assert [(row["sourceHousingTypeName"], row["sourceRow"], row["totalSupplyHouseholdCount"]) for row in rows] == [
        (None, None, None), (None, None, None), (None, None, None)]
    assert any("같은 의미의 원문 표 열에서 확인되지 않음" in item["reason"]
               for item in json.loads(result["json"])["data"]["unresolvedFields"])
    # 면적이 없는 LH 행은 맞댈 수 없어 조용히 넘긴다. LH 행 1줄과 PDF 공급행 3개가 달라도 오류가 아니다.
    assert not any("LH API" in warning for warning in result["warnings"])


def test_a_stray_lh_row_number_from_the_model_is_not_kept():
    # 모델이 예전처럼 LH 목록 번호를 써도 결과 공급행에 LH 행 식별자를 채우지 않는다.
    job_input = {"sourceSupplyRows": [
        {"source": "LH", "order": 1, "rowIdentifier": "LH:P:SUPPLY:1", "typeName": "51", "exclusiveArea": "", "supplyArea": "", "suppliedUnitCount": "200"}]}
    compact = {"data": {"rows": [{"rowIds": ["P2:T1:R2"], "complexName": "Sample Complex", "housingType": "16A",
                                  "target": "청년", "lhSourceRow": 1, "sourceRow": 1}]}, "sharedCounts": []}
    notice = {"data": {"announcement": {}, "receptionPlaces": [], "complexes": [], "schedules": [], "unresolvedFields": []},
              "notApplicable": [], "evidence": []}

    result = AnnouncementPdfExtractor(StageReplyModel(compact, notice)).extract(job(pdf(COVER, SUPPLY, RENT), job_input))

    [row] = json.loads(result["json"])["data"]["supplyRows"]
    assert (row["sourceHousingTypeName"], row["sourceRow"]) == ("16A", None)


def test_shared_count_for_several_types_stays_one_row_without_warning():
    # 공급 표가 두 주택형을 모집수 하나로만 안내하면 한 공급행에 subtypes로 두고 수를 나누거나 복제하지 않는다.
    subtypes = [{"name": "051.9800A", "exclusiveArea": 51.98, "commonArea": None, "constructedHouseholdCount": 468},
                {"name": "059.9100A", "exclusiveArea": 59.91, "commonArea": None, "constructedHouseholdCount": 183}]
    extraction = dict(EXTRACTION, data=dict(EXTRACTION["data"], supplyRows=[split_row("051.9800A/059.9100A", 240, None, subtypes=subtypes)]),
                      evidence=[])

    result = AnnouncementPdfExtractor(FakeModel(json.dumps(extraction, ensure_ascii=False))).extract(
        job(pdf(COVER, SUPPLY, RENT), MERGED_LH_INPUT))

    [row] = json.loads(result["json"])["data"]["supplyRows"]
    assert (row["sourceRow"], row["totalSupplyHouseholdCount"], len(row["subtypes"])) == (None, None, 2)
    assert "supplyRows[0].totalSupplyHouseholdCount" in {
        item["path"] for item in json.loads(result["json"])["data"]["unresolvedFields"]
    }
    assert not any("LH API" in warning for warning in result["warnings"])


def test_without_lh_rows_frame_is_not_sent():
    model = FakeModel()

    AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT), {"sourceSupplyRows": []}))

    prompt = stage_prompt(model, "supply")
    assert LH_FRAME not in prompt
    assert "[LH 공급 API 행]" not in prompt


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


def test_model_deadline_counts_from_attempt_start():
    model = FakeModel()
    started = time.monotonic()

    AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT)))

    assert len(model.deadlines) == 2
    budget = ATTEMPT_BUDGET.total_seconds() - openai.READ_TIMEOUT
    assert all(started + budget - 5 < deadline <= time.monotonic() + budget for deadline in model.deadlines)


class StageFailingModel:
    """공급 단계 호출은 일시 오류, 본문 단계 호출은 영구 오류로 끝나는 모델."""

    def __init__(self):
        self.prompts = []

    def chat(self, prompt, deadline):
        self.prompts.append(prompt)
        if "[추출 단계: 공급 표]" in prompt:
            raise TransientError("MODEL_TIMEOUT", "공급 단계 호출 시간 초과")
        raise PermanentError("MODEL_REJECTED", "본문 단계 호출 거절")


def test_supply_call_error_is_raised_before_the_notice_call_error():
    # 공급 단계 호출 오류를 본문 단계 호출 오류보다 먼저 알려, 일시 오류인 작업이 재시도 없이 실패하지 않게 한다.
    model = StageFailingModel()

    with pytest.raises(TransientError) as error:
        AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT)))

    assert error.value.code == "MODEL_TIMEOUT"
    assert len(model.prompts) == 2


def test_slow_pdf_fails_before_paid_call(monkeypatch):
    monkeypatch.setattr(announcement, "MIN_MODEL_TIME", ATTEMPT_BUDGET.total_seconds() + 1)
    model = FakeModel()

    with pytest.raises(PermanentError) as error:
        AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT)))

    assert error.value.code == "PDF_TOO_SLOW"
    assert model.prompts == []


def test_scanned_pdf_without_any_text_fails_before_paid_call():
    # 광주두암4처럼 쪽마다 글자가 없는 스캔본은 표를 읽을 수 없어 모델을 부르지 않고 관리자에게 직접 입력하라고 알린다.
    document = pymupdf.open()
    for _ in range(3):
        document.new_page().draw_rect(pymupdf.Rect(50, 50, 300, 300))
    model = FakeModel()

    with pytest.raises(PermanentError) as error:
        AnnouncementPdfExtractor(model).extract(job(document.tobytes()))

    assert error.value.code == "PDF_TEXT_MISSING"
    assert "이미지" in str(error.value)
    assert model.prompts == []


def test_unstructured_supply_table_fails_before_paid_call(monkeypatch):
    class BrokenTable:
        def extract(self):
            raise ValueError("table")

    monkeypatch.setattr(pymupdf.Page, "find_tables", lambda page: SimpleNamespace(tables=[BrokenTable()]))
    model = FakeModel()

    with pytest.raises(PermanentError) as error:
        AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT)))

    assert error.value.code == announcement.SUPPLY_TABLE_UNSTRUCTURED
    assert "원본 PDF 2, 3쪽" in error.value.message
    assert model.prompts == []


def test_text_only_supply_fallback_fails_before_paid_call(monkeypatch):
    monkeypatch.setattr(tables, "detected_tables", lambda page: ([], False))
    model = FakeModel()

    with pytest.raises(PermanentError) as error:
        AnnouncementPdfExtractor(model).extract(job(text_pdf(COVER, SUPPLY, RENT)))

    assert error.value.code == announcement.SUPPLY_TABLE_UNSTRUCTURED
    assert "원본 PDF 2, 3쪽" in error.value.message
    assert model.prompts == []


def test_missing_targets_against_rent_table_is_warning_not_failure(monkeypatch):
    # 표 인식기가 쪽마다 좌표 없는 임대조건표 하나를 찾은 것처럼 한다(쪽 전체가 표 영역).
    monkeypatch.setattr(tables, "detected_tables",
                        lambda page: ([tables.DetectedTable(RENT_CELLS, tuple(page.rect))], False))

    result = AnnouncementPdfExtractor(FakeModel()).extract(job(pdf(COVER, SUPPLY, RENT)))

    assert "supplyRows: 공급대상 0개가 임대조건표 4줄보다 적어 빠진 대상이 있을 수 있음" in result["warnings"]


def structured_table_pdf():
    document = pymupdf.open()
    page = document.new_page()
    page.insert_text((20, 20), "Sample Notice", fontname="korea", fontsize=9)
    page.insert_text((20, 35), "Address: Test Road; total households 100; parking 30", fontname="korea", fontsize=8)
    xs, ys = [20, 115, 210, 305, 380, 475, 575], [48, 78, 108]
    for x in xs:
        page.draw_line((x, ys[0]), (x, ys[-1]))
    for y in ys:
        page.draw_line((xs[0], y), (xs[-1], y))
    rows = [
        ["단지명", "주택형", "공급대상", "모집호수", "임대보증금(천원)", "월임대료(원)"],
        ["Test", "26A", "청년", "20", "46,920", "178,670"],
    ]
    for row_index, row in enumerate(rows):
        for column, value in enumerate(row):
            page.insert_text((xs[column] + 2, ys[row_index] + 18), value, fontname="korea", fontsize=7)
    return document.tobytes()


def test_two_stage_extract_merges_fields_and_validates_row_ids_against_original_cells():
    supply = {
        "data": {
            "complexes": [{"sourceComplexName": "Test", "totalHouseholdCount": None, "parkingCount": None}],
            "supplyRows": [{
                "complexReference": {"sourceComplexName": "Test"}, "sourceHousingTypeName": "26A",
                "expectedMoveInMonth": None, "totalSupplyHouseholdCount": 20,
                "exclusiveArea": None, "commonArea": None, "supplyArea": None,
                "constructedHouseholdCount": None, "subtypes": [], "targets": [{
                    "target": "청년", "supplyHouseholdCount": None, "reserveCount": None,
                    "rentalDeposit": 46920000, "monthlyRent": 178670, "convertedDeposit": None,
                    "applicationCondition": None,
                }],
            }],
            "unresolvedFields": [],
        },
        "sharedCounts": [], "notApplicable": [],
        "evidence": [
            {"path": "complexes[0]", "page": 1, "quote": "Test"},
            {"path": "supplyRows[0]", "page": 1, "quote": "Test 26A 20"},
            {"path": "supplyRows[0].targets[0]", "page": 1, "quote": "청년 46,920 178,670"},
        ],
        "sourceRows": [
            {"path": "supplyRows[0]", "rowIds": ["P1:T1:R2"]},
            {"path": "supplyRows[0].targets[0]", "rowIds": ["P1:T1:R2"]},
        ],
    }
    notice = {
        "data": {
            "announcement": {"name": "Sample Notice", "postedDate": None, "applicationStartDate": None,
                             "applicationEndDate": None, "winnerAnnouncementDate": None},
            "receptionPlaces": [],
            "complexes": [{"sourceComplexName": "Test", "address": "Test Road", "totalHouseholdCount": 100,
                           "parkingCount": 30}],
            "schedules": [], "unresolvedFields": [],
        },
        "notApplicable": [],
        "evidence": [
            {"path": "announcement.name", "page": 1, "quote": "Sample Notice"},
            {"path": "complexes[0]", "page": 1, "quote": "Test"},
        ],
    }
    model = FakeModel(stage_contents={
        "supply": json.dumps(supply, ensure_ascii=False),
        "notice": json.dumps(notice, ensure_ascii=False),
    })

    job_input = {"sourceSupplyRows": [{"source": "LH", "typeName": "26A", "exclusiveArea": "26", "supplyArea": "", "suppliedUnitCount": "80"}]}
    result = AnnouncementPdfExtractor(model).extract(job(structured_table_pdf(), job_input))

    tree = json.loads(result["json"])
    assert len(model.prompts) == 2
    assert "P1:T1:R2" in stage_prompt(model, "supply")
    assert "Test" in stage_prompt(model, "notice")
    assert tree["data"]["complexes"] == [{
        "sourceComplexName": "Test", "address": "Test Road", "totalHouseholdCount": 100,
        "parkingCount": 30,
    }]
    assert tree["data"]["supplyRows"][0]["targets"][0]["rentalDeposit"] == 46920000
    assert tree["data"]["supplyRows"][0]["totalSupplyHouseholdCount"] == 20
    # PDF 공급행에 전용면적이 없어 LH 모집수와 맞대지 않는다.
    assert not any("LH API" in warning for warning in result["warnings"])
    assert not any("원문 표 행 ID" in warning or "지정한 원문 표 행" in warning for warning in result["warnings"])


def test_supply_table_the_code_can_read_skips_the_supply_model_call():
    notice = {
        "data": {
            "announcement": {"name": "Sample Notice"}, "receptionPlaces": [],
            "complexes": [{"sourceComplexName": "Test", "address": "Test Road", "totalHouseholdCount": 100,
                           "parkingCount": 30}],
            "schedules": [], "unresolvedFields": [],
        },
        "notApplicable": [],
        "evidence": [{"path": "announcement.name", "page": 1, "quote": "Sample Notice"}],
    }
    model = FakeModel(stage_contents={"notice": json.dumps(notice, ensure_ascii=False)})

    result = AnnouncementPdfExtractor(model, FakeJudge()).extract(job(structured_table_pdf()))

    tree = json.loads(result["json"])
    assert len(model.prompts) == 1 and "[추출 단계: 공고 본문]" in model.prompts[0]
    row = tree["data"]["supplyRows"][0]
    assert (row["complexReference"]["sourceComplexName"], row["sourceHousingTypeName"],
            row["totalSupplyHouseholdCount"]) == ("Test", "26A", 20)
    assert (row["targets"][0]["target"], row["targets"][0]["rentalDeposit"],
            row["targets"][0]["monthlyRent"]) == ("청년", 46920000, 178670)
    assert {"path": "supplyRows[0].targets[0]", "page": 1} in [
        {"path": item["path"], "page": item["page"]} for item in tree["evidence"]]
    assert result["usage"] == USAGE


def area_table_pdf():
    document = pymupdf.open()
    grid_page(document, [
        ["단지명", "주택형", "공급대상", "모집호수", "주거전용", "주거공용"],
        ["Test", "26A", "청년", "20", "26.37", "13.07"],
    ]).insert_text((20, 30), "Sample Notice", fontname="korea", fontsize=8)
    return document.tobytes()


def test_lh_rows_keep_the_code_path_and_are_checked_by_area_against_the_pdf_row():
    # LH가 같은 전용면적을 신청자 구분으로 두 줄(12+10) 보내도 공급행은 PDF 한 행이고, 모집수 합계만 맞대 경고한다.
    model = FakeModel()
    job_input = {"sourceSupplyRows": [
        {"source": "LH", "order": 0, "rowIdentifier": "LH:P:SUPPLY:0", "complexLabel": "Test", "typeName": "26A(우선)",
         "exclusiveArea": "26.3700", "supplyArea": "", "suppliedUnitCount": "12", "totalUnitCount": ""},
        {"source": "LH", "order": 1, "rowIdentifier": "LH:P:SUPPLY:1", "complexLabel": "Test", "typeName": "26A(일반)",
         "exclusiveArea": "26.37", "supplyArea": "", "suppliedUnitCount": "10", "totalUnitCount": ""}]}

    result = AnnouncementPdfExtractor(model, FakeJudge()).extract(job(area_table_pdf(), job_input))

    assert len(model.prompts) == 1 and "[추출 단계: 공고 본문]" in model.prompts[0]
    [row] = json.loads(result["json"])["data"]["supplyRows"]
    assert (row["sourceHousingTypeName"], row["exclusiveArea"], row["totalSupplyHouseholdCount"], row["sourceRow"]) == (
        "26A", 26.37, 20, None)
    assert [warning for warning in result["warnings"] if "LH API" in warning] == [
        "supplyRows[0].totalSupplyHouseholdCount: 같은 전용면적(26.37) LH API 공급행 2줄의 모집수 합계 22와 다름(20)"]


def test_one_pdf_row_written_once_per_lh_row_comes_out_once(monkeypatch):
    # 사천벌리1: 공급표 머리글(공급형별·계약면적 > 전용)을 읽지 못해 주택형을 확인하지 못하면, 모델이 LH 행마다 같은 표 행을
    # 따로 쓴 항목(LH 이름 26A를 옮긴 것 포함)이 공급행 두 개로 나왔다. 같은 표 행의 같은 주택형은 공급행 하나다.
    from test_tables import SACHEON_SUPPLY_CELLS
    monkeypatch.setattr(tables, "detected_tables", lambda page: (
        [tables.DetectedTable(SACHEON_SUPPLY_CELLS, tuple(page.rect))] if page.number == 1 else [], False))
    job_input = {"sourceSupplyRows": [
        {"source": "LH", "order": 0, "rowIdentifier": "LH:P:SUPPLY:0", "complexLabel": "사천벌리1", "typeName": "26A",
         "exclusiveArea": "26.37", "supplyArea": "", "suppliedUnitCount": "60", "totalUnitCount": ""},
        {"source": "LH", "order": 1, "rowIdentifier": "LH:P:SUPPLY:1", "complexLabel": "사천벌리1", "typeName": "26",
         "exclusiveArea": "26.37", "supplyArea": "", "suppliedUnitCount": "50", "totalUnitCount": ""}]}
    supply = {"data": {"rows": [
        {"rowIds": ["P2:T1:R4"], "housingType": "26", "target": "가군", "exclusiveArea": 26.37},
        {"rowIds": ["P2:T1:R4"], "housingType": "26A", "target": "나군", "exclusiveArea": 26.37},
    ]}, "sharedCounts": []}
    notice = {"data": {"announcement": {}, "receptionPlaces": [], "complexes": [], "schedules": [], "unresolvedFields": []},
              "notApplicable": [], "evidence": []}

    result = AnnouncementPdfExtractor(StageReplyModel(supply, notice)).extract(job(text_pdf(COVER, SUPPLY), job_input))

    [row] = json.loads(result["json"])["data"]["supplyRows"]
    assert (row["sourceHousingTypeName"], row["exclusiveArea"], row["sourceRow"]) == ("26", 26.37, None)
    # 이 공급표에는 공급대상 열이 없어 대상 이름은 미확인이지만, 두 항목은 한 공급행의 대상 둘이다.
    assert len(row["targets"]) == 2



def test_targets_named_by_applicant_group_headers_of_the_rent_table_keep_their_names_and_amounts(monkeypatch):
    # 사천벌리1: 임대조건표의 가군·나군은 금액 열 위의 병합 머리글이다. 맞게 읽은 대상 이름과 금액이 모두 비워졌다.
    from test_tables import SACHEON_RENT_CELLS, SACHEON_SUPPLY_CELLS
    cells = {1: SACHEON_SUPPLY_CELLS, 2: SACHEON_RENT_CELLS}
    monkeypatch.setattr(tables, "detected_tables", lambda page: (
        [tables.DetectedTable(cells[page.number], tuple(page.rect))] if page.number in cells else [], False))
    supply = {"data": {"rows": [
        {"rowIds": ["P2:T1:R4", "P3:T1:R4"], "housingType": "26", "target": "가군", "reserveCount": 110,
         "rentalDeposit": 2520000, "monthlyRent": 50160},
        {"rowIds": ["P2:T1:R4", "P3:T1:R4"], "housingType": "26", "target": "나군", "reserveCount": 110,
         "rentalDeposit": 7648000, "monthlyRent": 115940},
    ]}, "sharedCounts": [{"rowIndexes": [0, 1], "field": "reserveCount", "count": 110, "rowIds": ["P2:T1:R4"]}]}
    notice = {"data": {"announcement": {}, "receptionPlaces": [], "complexes": [], "schedules": [], "unresolvedFields": []},
              "notApplicable": [], "evidence": []}

    result = AnnouncementPdfExtractor(StageReplyModel(supply, notice)).extract(job(text_pdf(COVER, SUPPLY, RENT), {}))

    tree = json.loads(result["json"])
    [row] = tree["data"]["supplyRows"]
    assert [(target["target"], target["rentalDeposit"], target["monthlyRent"]) for target in row["targets"]] == [
        ("가군", 2520000, 50160), ("나군", 7648000, 115940)]
    assert not any(".targets[" in finding["path"] for finding in tree["data"]["unresolvedFields"])
    assert len(tree["sharedCounts"]) == 1

def test_announcement_wide_application_condition_reaches_each_target_with_its_evidence(monkeypatch):
    """본문 단계의 신청자격(공고 전체 조건)이 원문 대조 검산 뒤에도 모든 대상의 신청 조건과 근거로 남는다."""
    from test_tables import SACHEON_RENT_CELLS, SACHEON_SUPPLY_CELLS
    cells = {1: SACHEON_SUPPLY_CELLS, 2: SACHEON_RENT_CELLS}
    monkeypatch.setattr(tables, "detected_tables", lambda page: (
        [tables.DetectedTable(cells[page.number], tuple(page.rect))] if page.number in cells else [], False))
    supply = {"data": {"rows": [
        {"rowIds": ["P2:T1:R4", "P3:T1:R4"], "housingType": "26", "target": "가군", "reserveCount": 110,
         "rentalDeposit": 2520000, "monthlyRent": 50160},
    ]}, "sharedCounts": []}
    notice = {"data": {"announcement": {}, "receptionPlaces": [], "complexes": [], "schedules": [], "unresolvedFields": [],
                       "applicationConditions": [{"target": None, "condition": "영구임대 예비입주자 자격"}]},
              "notApplicable": [], "evidence": [{"path": "applicationConditions[0]", "page": 1, "quote": "영구임대주택 예비입주자 모집"}]}

    result = AnnouncementPdfExtractor(StageReplyModel(supply, notice)).extract(job(text_pdf(COVER, SUPPLY, RENT), {}))

    tree = json.loads(result["json"])
    [row] = tree["data"]["supplyRows"]
    assert [target["applicationCondition"] for target in row["targets"]] == ["영구임대 예비입주자 자격"]
    assert {(item["path"], item["page"]) for item in tree["evidence"] if item["path"].endswith(".applicationCondition")} == {
        ("supplyRows[0].targets[0].applicationCondition", 1)}
    assert "applicationConditions" not in tree["data"]


def test_gh_frame_rows_keep_the_code_path_and_link_the_grouped_row_by_district_and_type():
    model = FakeModel()
    job_input = {"sourceSupplyRows": [
        {"source": "GH", "order": 0, "rowIdentifier": "GH:9:Test:26A", "complexLabel": "Test", "typeName": "26A 청년",
         "exclusiveArea": "", "supplyArea": "", "suppliedUnitCount": "20", "totalUnitCount": ""},
        {"source": "GH", "order": 1, "rowIdentifier": "GH:9:Test:36A", "complexLabel": "Test", "typeName": "36A",
         "exclusiveArea": "", "supplyArea": "", "suppliedUnitCount": "5", "totalUnitCount": ""}]}

    result = AnnouncementPdfExtractor(model, FakeJudge()).extract(job(structured_table_pdf(), job_input))

    assert len(model.prompts) == 1 and "[추출 단계: 공고 본문]" in model.prompts[0]
    assert [row["sourceRow"] for row in json.loads(result["json"])["data"]["supplyRows"]] == ["GH:9:Test:26A"]
    assert "PDF에서 찾지 못한 GH 공급행 틀 1개: Test 36A" in result["warnings"]
    assert not any("LH API" in warning for warning in result["warnings"])


def test_gh_frame_is_not_sent_to_the_supply_model_and_a_model_row_number_is_not_kept():
    # Jev가 수량 열을 확인하지 못해 공급표를 모델이 읽어도 GH 틀은 프롬프트에 넣지 않고 코드가 맞춘다.
    supply = {"data": {"complexes": [], "unresolvedFields": [], "supplyRows": [{
        "complexReference": {"sourceComplexName": "Test"}, "sourceHousingTypeName": "26A",
        "totalSupplyHouseholdCount": 20, "subtypes": [], "targets": [{"target": "청년"}]}]},
        "sharedCounts": [], "notApplicable": [], "evidence": [],
        "sourceRows": [{"path": "supplyRows[0]", "rowIds": ["P1:T1:R2"]},
                       {"path": "supplyRows[0].targets[0]", "rowIds": ["P1:T1:R2"]}]}
    model = FakeModel(stage_contents={"supply": json.dumps(supply, ensure_ascii=False)})
    job_input = {"sourceSupplyRows": [
        {"source": "GH", "order": 0, "rowIdentifier": "GH:9:Test:26A", "complexLabel": "Test", "typeName": "26A 청년",
         "exclusiveArea": "", "supplyArea": "", "suppliedUnitCount": "20", "totalUnitCount": ""}]}

    result = AnnouncementPdfExtractor(model, FakeJudge(choice="existing_waitlist")).extract(job(structured_table_pdf(), job_input))

    assert "[LH 공급 API 행]" not in stage_prompt(model, "supply")
    assert [row["sourceRow"] for row in json.loads(result["json"])["data"]["supplyRows"]] == ["GH:9:Test:26A"]
    assert not any("LH API" in warning or "GH 공급행 틀" in warning for warning in result["warnings"])


def test_supply_table_continued_without_header_and_a_tableless_next_page_reach_the_result():
    document = pymupdf.open()
    grid_page(document, [
        ["단지명", "주택형", "공급대상", "모집호수 예비자", "주거전용", "주거공용"],
        ["Alpha", "26A", "청년", "20", "26.73", "13.07"],
    ])
    # 다음 쪽 표는 머리글이 없고 첫 행 단지명이 빈칸이라 앞 쪽의 열 역할과 단지를 이어받는다.
    grid_page(document, [["", "36A", "고령자", "3", "36.92", "18.06"], ["Beta", "44A", "청년", "4", "44.81", "21.92"]])
    document.new_page().insert_text((20, 72), "주택형 36B 청년 5 36.91 18.05 공급대상", fontname="korea", fontsize=7)
    model = FakeModel()

    judge = FakeJudge()

    result = AnnouncementPdfExtractor(model, judge).extract(job(document.tobytes()))

    tree = json.loads(result["json"])
    assert len(model.prompts) == 1
    assert [(row["complexReference"]["sourceComplexName"], row["sourceHousingTypeName"],
             row["targets"][0]["reserveCount"], row["exclusiveArea"]) for row in tree["data"]["supplyRows"]] == [
        ("Alpha", "26A", 20, 26.73), ("Alpha", "36A", 3, 36.92), ("Beta", "44A", 4, 44.81)]
    assert "원본 3쪽의 공급표를 표로 읽지 못해 공급행이 빠졌을 수 있습니다. 원본을 확인해 주세요." in result["warnings"]
    # 머리글 없는 이어짐 표는 앞 표와 같은 열이라 Jev에 다시 묻지 않는다.
    assert len(judge.calls) == 1


@pytest.mark.parametrize("judge", [
    None,
    FakeJudge(choice="existing_waitlist"),
    FakeJudge(probability=0.6),
    FakeJudge(error=jev.JevError("Jev가 요청을 거절했습니다(HTTP 403).")),
], ids=["no-key", "different-meaning", "low-probability", "jev-failed"])
def test_supply_table_goes_to_the_model_unless_jev_confirms_the_count_column(judge):
    model = FakeModel()

    AnnouncementPdfExtractor(model, judge).extract(job(structured_table_pdf()))

    assert len(model.prompts) == 2 and any("[추출 단계: 공급 표]" in prompt for prompt in model.prompts)


def test_jev_is_asked_about_the_mapped_count_column_with_its_header_and_rows():
    judge = FakeJudge()

    AnnouncementPdfExtractor(FakeModel(), judge).extract(job(structured_table_pdf()))

    (state, headers, reserve_only), = judge.calls
    assert list(headers.values()) == ["모집호수"]
    assert state["table"]["rows"][0]["모집호수"] == "20"
    assert reserve_only is False


def test_one_count_column_judged_differently_sends_the_whole_supply_table_to_the_model():
    model = FakeModel()
    judge = FakeJudge(by_header={"모집호수 예비자": "existing_waitlist"})

    AnnouncementPdfExtractor(model, judge).extract(job(two_quantity_tables_pdf()))

    assert len(judge.calls) == 2
    assert any("[추출 단계: 공급 표]" in prompt for prompt in model.prompts)


def reserve_tables_pdf(first_header):
    document = pymupdf.open()
    page = grid_page(document, [
        ["단지명", "주택형", "공급대상", first_header, "주거전용", "주거공용"],
        ["Alpha", "26A", "청년", "20", "26.73", "13.07"],
    ])
    page.insert_text((20, 30), "2026 예비입주자 추가모집 공고", fontname="korea", fontsize=8)
    return document.tobytes()


def test_reserve_recruitment_notice_confirms_the_reserve_column_without_winner_options():
    model = FakeModel()
    judge = FakeJudge()

    result = AnnouncementPdfExtractor(model, judge).extract(job(reserve_tables_pdf("모집호수 예비자")))

    assert [reserve_only for _, _, reserve_only in judge.calls] == [True]
    assert len(model.prompts) == 1
    assert json.loads(result["json"])["data"]["supplyRows"][0]["targets"][0]["reserveCount"] == 20


def test_winner_count_column_in_a_reserve_recruitment_notice_goes_to_the_model():
    model = FakeModel()
    judge = FakeJudge()

    AnnouncementPdfExtractor(model, judge).extract(job(reserve_tables_pdf("당첨자 수")))

    # 예비자만 모집하는 공고의 당첨자 열은 Jev에 물을 것도 없이 코드가 확정하지 않는다.
    assert judge.calls == []
    assert any("[추출 단계: 공급 표]" in prompt for prompt in model.prompts)


def test_notice_note_path_written_with_empty_brackets_reaches_the_result_readable():
    notice = {
        "data": {
            "announcement": {"name": "Sample Notice"}, "receptionPlaces": [], "complexes": [], "schedules": [],
            "unresolvedFields": [{"path": "complexes[].parkingCount", "reason": "주차대수를 확인하지 못함"}],
        },
        "notApplicable": [], "evidence": [],
    }
    model = FakeModel(stage_contents={"notice": json.dumps(notice, ensure_ascii=False)})

    result = AnnouncementPdfExtractor(model, FakeJudge()).extract(job(structured_table_pdf()))

    paths = [item["path"] for item in json.loads(result["json"])["data"]["unresolvedFields"]]
    assert "complexes[*].parkingCount" in paths and "complexes[].parkingCount" not in paths


def test_several_reception_places_reach_the_result_in_notice_order():
    # 인터넷 접수와 현장 접수처 여러 곳을 한 곳으로 줄이지 않고, 접수처마다의 근거·해당 없음 경로도 그대로 둔다.
    places = [
        {"name": "LH청약플러스", "method": "ONLINE", "address": None, "contact": "1600-1004",
         "url": "https://apply.lh.or.kr", "guidance": "모바일 앱으로도 신청 가능"},
        {"name": "Test 견본주택", "method": "VISIT", "address": "Test Road", "contact": "032-000-0000", "url": None,
         "guidance": "고령자·장애인 등 인터넷 취약계층만 현장 접수"},
        {"name": "거주지 관할 행정복지센터", "method": "VISIT", "address": None, "contact": None, "url": None,
         "guidance": None},
    ]
    notice = {
        "data": {"announcement": {"name": "Sample Notice"}, "receptionPlaces": places, "complexes": [],
                 "schedules": [], "unresolvedFields": []},
        "notApplicable": [{"path": "receptionPlaces[2].address", "reason": "신청자 거주지마다 다름"}],
        "evidence": [{"path": f"receptionPlaces[{index}]", "page": 1, "quote": "Sample Notice"} for index in range(3)],
    }
    model = FakeModel(stage_contents={"notice": json.dumps(notice, ensure_ascii=False)})

    result = AnnouncementPdfExtractor(model, FakeJudge()).extract(job(structured_table_pdf()))

    tree = json.loads(result["json"])
    assert tree["data"]["receptionPlaces"] == places
    assert [item["path"] for item in tree["notApplicable"]] == ["receptionPlaces[2].address"]
    assert [item["path"] for item in tree["evidence"] if item["path"].startswith("receptionPlaces")] == [
        "receptionPlaces[0]", "receptionPlaces[1]", "receptionPlaces[2]"]
    assert not any("receptionPlaces" in warning for warning in result["warnings"])


@pytest.mark.parametrize("reception", [
    {"receptionPlace": {"name": "LH청약플러스", "method": "ONLINE", "address": None, "contact": None, "url": None}},
    {"receptionPlaces": {"name": "LH청약플러스", "method": "ONLINE", "address": None, "contact": None, "url": None,
                         "guidance": None}},
])
def test_notice_reply_without_a_reception_place_list_is_rejected(reception):
    notice = {"data": {"announcement": {"name": "Sample Notice"}, **reception, "complexes": [], "schedules": [],
                       "unresolvedFields": []}, "notApplicable": [], "evidence": []}

    with pytest.raises(PermanentError) as error:
        AnnouncementPdfExtractor(StageReplyModel(None, notice), FakeJudge()).extract(job(structured_table_pdf()))

    assert error.value.code == "MODEL_RESPONSE_INVALID"


class StageReplyModel:
    """단계마다 정해 둔 응답 JSON을 그대로 돌려주는 모델."""

    def __init__(self, supply, notice):
        self.replies = {"supply": supply, "notice": notice}

    def chat(self, prompt, deadline):
        stage = "supply" if "[추출 단계: 공급 표]" in prompt else "notice"
        return Reply(json.dumps(self.replies[stage], ensure_ascii=False), USAGE)


# 공급행 근거 행 ID가 입력 표에 없고(공급행 0), 다른 공급행은 표와 다른 모집수(99)를 냈으며,
# 본문 단계는 같은 단지의 주소를 둘로 내고 범위 밖 근거 쪽(99)을 쓴 공고의 추출 결과(검산을 두 번 하던 때 그대로).
SOURCE_CHECKED_TREE = {'data': {'announcement': {'name': 'Sample Notice'},
          'receptionPlaces': [],
          'complexes': [{'sourceComplexName': 'Test',
                         'address': None,
                         'totalHouseholdCount': 100,
                         'parkingCount': 30}],
          'supplyRows': [{'complexReference': {'sourceComplexName': ''},
                          'sourceHousingTypeName': None,
                          'sourceRow': None,
                          'expectedMoveInMonth': None,
                          'totalSupplyHouseholdCount': None,
                          'exclusiveArea': None,
                          'commonArea': None,
                          'supplyArea': None,
                          'constructedHouseholdCount': None,
                          'subtypes': [],
                          'targets': [{'target': '',
                                       'supplyHouseholdCount': None,
                                       'reserveCount': None,
                                       'rentalDeposit': None,
                                       'monthlyRent': None,
                                       'convertedDeposit': None,
                                       'applicationCondition': None}]},
                         {'complexReference': {'sourceComplexName': 'Test'},
                          'sourceHousingTypeName': '26A',
                          'sourceRow': None,
                          'expectedMoveInMonth': None,
                          'totalSupplyHouseholdCount': None,
                          'exclusiveArea': None,
                          'commonArea': None,
                          'supplyArea': None,
                          'constructedHouseholdCount': None,
                          'subtypes': [],
                          # 모델이 빠뜨린 대상 모집수는 이 공급행만 가리키는 원문 행의 모집호수 칸에서 읽는다.
                          # 모델이 낸 공급행 모집수(99)는 원문과 달라 비운다.
                          'targets': [{'target': '청년',
                                       'supplyHouseholdCount': 20,
                                       'reserveCount': None,
                                       'rentalDeposit': None,
                                       'monthlyRent': None,
                                       'convertedDeposit': None,
                                       'applicationCondition': None}]}],
          'schedules': [],
          'unresolvedFields': [{'path': 'supplyRows[0].complexReference.sourceComplexName',
                                'reason': '추출한 값이 같은 의미의 원문 표 열에서 확인되지 않음'},
                               {'path': 'supplyRows[0].sourceHousingTypeName',
                                'reason': '추출한 값이 같은 의미의 원문 표 열에서 확인되지 않음'},
                               {'path': 'supplyRows[0].targets[0].target',
                                'reason': '추출한 값이 같은 의미의 원문 표 열에서 확인되지 않음'},
                               {'path': 'supplyRows[1].targets[0].reserveCount',
                                'reason': '이번 공고의 예비자 모집수를 표에서 확인하지 못함'},
                               {'path': 'supplyRows[0]', 'reason': '응답의 원문 표 행 ID가 입력 표와 일치하지 않음'},
                               {'path': 'supplyRows[0].targets[0]', 'reason': '응답의 원문 표 행 ID가 입력 표와 일치하지 않음'},
                               {'path': 'supplyRows[0].targets[0].reserveCount',
                                'reason': '응답의 원문 표 행 ID가 입력 표와 일치하지 않음; 수치 원문을 확인하지 못함'},
                               {'path': 'supplyRows[1].totalSupplyHouseholdCount',
                                'reason': '추출한 수치가 같은 의미의 원문 표 열에서 확인되지 않음'},
                               {'path': 'complexes[0].address', 'reason': '표·본문 단계의 값이 달라 미확인으로 남김'}]},
 'sharedCounts': [],
 'notApplicable': [],
 'evidence': [{'path': 'supplyRows[0]', 'page': 1, 'quote': ''},
              {'path': 'supplyRows[0].targets[0]', 'page': 1, 'quote': ''},
              {'path': 'supplyRows[1]', 'page': 1, 'quote': 'Test\t\t26A\t\t청년\t\t20\t\t46,920\t\t178,670'},
              {'path': 'supplyRows[1].targets[0]',
               'page': 1,
               'quote': 'Test\t\t26A\t\t청년\t\t20\t\t46,920\t\t178,670'},
              {'path': 'complexes[0]', 'page': 1, 'quote': 'Test\t\t26A\t\t청년\t\t20\t\t46,920\t\t178,670'},
              {'path': 'announcement.name', 'page': 99, 'quote': 'Sample Notice'}]}

SOURCE_CHECKED_WARNINGS = ['공급표·임대조건표 쪽을 찾지 못해 전체 1쪽을 보냈습니다.',
 'announcement.name: 첨부한 쪽 범위를 벗어난 근거 쪽 번호(99)',
 'complexes[0].address: 표·본문 단계의 값이 달라 미확인으로 남김',
 'supplyRows[0]: 응답의 원문 표 행 ID가 입력 표와 일치하지 않음',
 'supplyRows[0].targets[0]: 응답의 원문 표 행 ID가 입력 표와 일치하지 않음',
 'announcement.name: 첨부한 쪽 범위를 벗어난 근거 쪽 번호(99)']


def test_source_check_findings_reach_the_result_once():
    supply = {"data": {"rows": [
        {"rowIds": ["P1:T1:R9"], "complexName": "Test", "housingType": "26A", "target": "청년", "reserveCount": 20},
        {"rowIds": ["P1:T1:R2"], "complexName": "Test", "housingType": "26A", "target": "청년",
         "totalSupplyHouseholdCount": 99},
    ]}}
    notice = {
        "data": {"announcement": {"name": "Sample Notice"}, "receptionPlaces": [], "schedules": [], "unresolvedFields": [],
                 "complexes": [
                     {"sourceComplexName": "Test", "address": "A Road", "totalHouseholdCount": 100, "parkingCount": 30},
                     {"sourceComplexName": "Test", "address": "B Road", "totalHouseholdCount": 100, "parkingCount": 30}]},
        "notApplicable": [], "evidence": [{"path": "announcement.name", "page": 99, "quote": "Sample Notice"}],
    }

    result = AnnouncementPdfExtractor(StageReplyModel(supply, notice)).extract(job(structured_table_pdf()))

    assert json.loads(result["json"]) == SOURCE_CHECKED_TREE
    # 원문 대조 검산을 공급 단계 조립에서 한 번만 하므로 그 경고는 본문 쪽 번호·단지 병합 경고보다 앞에 온다.
    # 내용과 개수는 두 번 검산하던 때(SOURCE_CHECKED_WARNINGS)와 같다.
    assert sorted(result["warnings"]) == sorted(SOURCE_CHECKED_WARNINGS)
    assert result["warnings"] == [
        "공급표·임대조건표 쪽을 찾지 못해 전체 1쪽을 보냈습니다.",
        "supplyRows[0]: 응답의 원문 표 행 ID가 입력 표와 일치하지 않음",
        "supplyRows[0].targets[0]: 응답의 원문 표 행 ID가 입력 표와 일치하지 않음",
        "announcement.name: 첨부한 쪽 범위를 벗어난 근거 쪽 번호(99)",
        "complexes[0].address: 표·본문 단계의 값이 달라 미확인으로 남김",
        "announcement.name: 첨부한 쪽 범위를 벗어난 근거 쪽 번호(99)",
    ]


def address_table_pdf(address_cells: list[str]):
    document = pymupdf.open()
    page = document.new_page()
    page.insert_text((20, 20), "Sample Notice", fontname="korea", fontsize=9)
    xs = [20, 90, 160, 230, 300, 380, 460, 575]
    ys = [48 + 30 * index for index in range(len(address_cells) + 2)]
    for x in xs:
        page.draw_line((x, ys[0]), (x, ys[-1]))
    for y in ys:
        page.draw_line((xs[0], y), (xs[-1], y))
    rows = [["단지명", "주택형", "공급대상", "모집호수", "임대보증금(천원)", "월임대료(원)", "소재지"]]
    rows += [["Test", f"{26 + index}A", "청년", "20", "46,920", "178,670", cell] for index, cell in enumerate(address_cells)]
    for row_index, row in enumerate(rows):
        for column, value in enumerate(row):
            page.insert_text((xs[column] + 2, ys[row_index] + 18), value, fontname="korea", fontsize=5)
    return document.tobytes()


def notice_with_address(address):
    return json.dumps({"data": {
        "announcement": {"name": "Sample Notice"}, "receptionPlaces": [], "schedules": [], "unresolvedFields": [],
        "complexes": [{"sourceComplexName": "Test", "address": address, "totalHouseholdCount": None, "parkingCount": None}],
    }, "notApplicable": [], "evidence": [{"path": "announcement.name", "page": 1, "quote": "Sample Notice"}]}, ensure_ascii=False)


def test_complex_address_is_read_from_the_supply_table_by_code_and_matches_an_abbreviated_notice_address():
    # 소재지 칸은 병합돼 첫 행에만 있고, 본문 단계는 시·도를 줄여 같은 주소를 낸다.
    model = FakeModel(stage_contents={"notice": notice_with_address("서울시 중구 세종대로 110")})

    result = AnnouncementPdfExtractor(model, FakeJudge()).extract(
        job(address_table_pdf(["서울특별시 중구 세종대로 110(태평로1가) 일원", ""])))

    tree = json.loads(result["json"])
    assert tree["data"]["complexes"][0]["address"] == "서울특별시 중구 세종대로 110"
    assert "complexes[0].address" not in {item["path"] for item in tree["data"]["unresolvedFields"]}
    assert any(item["path"] == "complexes[0]" and item["page"] == 1 for item in tree["evidence"])
    assert len(model.prompts) == 1


def test_supply_table_address_differing_from_the_notice_address_stays_unresolved():
    # 본문 단계가 접수처 주소를 단지 주소로 잘못 냈다면 어느 쪽도 고르지 않는다.
    model = FakeModel(stage_contents={"notice": notice_with_address("서울특별시 강남구 테헤란로 1")})

    result = AnnouncementPdfExtractor(model, FakeJudge()).extract(job(address_table_pdf(["서울특별시 중구 세종대로 110", ""])))

    tree = json.loads(result["json"])
    assert tree["data"]["complexes"][0]["address"] is None
    assert "complexes[0].address" in {item["path"] for item in tree["data"]["unresolvedFields"]}


def test_two_different_addresses_for_one_complex_in_the_supply_table_stay_unresolved():
    model = FakeModel(stage_contents={"notice": notice_with_address(None)})

    result = AnnouncementPdfExtractor(model, FakeJudge()).extract(
        job(address_table_pdf(["서울특별시 중구 세종대로 110", "서울특별시 중구 을지로 12"])))

    tree = json.loads(result["json"])
    assert tree["data"]["complexes"][0]["address"] is None
    assert any(item["path"] == "complexes[0].address" and "소재지" in item["reason"]
               for item in tree["data"]["unresolvedFields"])


@pytest.mark.parametrize("names, lh_labels, first_confirmed", [
    # 모델이 블록마다 다른 이름을 냈다. 표 위 가장 가까운 제목이라 확인되고 공급행이 따로 남는다.
    (("광주선운2 A-1블록", "광주선운2 A-3블록"), (), True),
    # 모델이 두 행 모두 A-1블록으로 냈다. LH 단지명에 A-3블록이 있어 A-3블록 표의 행은 확인되지 않는다.
    (("광주선운2 A-1블록", "광주선운2 A-1블록"), ("광주선운2 A-1블록", "광주선운2 A-3블록"), False),
    # LH 행도 없이 한 이름만 냈다. 표 위에서 찾히는 후보가 하나뿐이라 가장 가까운지 가릴 수 없어 제목 대조를 하지 않는다.
    (("광주선운2 A-1블록", "광주선운2 A-1블록"), (), False),
    # 한 행에만 이름을 냈다. LH 단지명이 후보로 들어와야(작업 입력 → 공급 단계 → 조립) 가장 가까운 제목임을 가린다.
    (("광주선운2 A-1블록", ""), ("광주선운2 A-1블록", "광주선운2 A-3블록"), True),
    (("광주선운2 A-1블록", ""), (), False),
])
def test_block_heading_complex_names_are_checked_against_the_nearest_heading(names, lh_labels, first_confirmed):
    from test_pages import block_heading_tables_pdf
    rows = [{"rowIds": [row_id], "complexName": name, "housingType": "55B-1", "target": "신혼부부", "reserveCount": 20}
            for row_id, name in zip(("P1:T1:R2", "P1:T2:R2"), names)]
    notice = {"data": {"announcement": {}, "receptionPlaces": [], "complexes": [], "schedules": [], "unresolvedFields": []}}
    job_input = {"sourceSupplyRows": [{"source": "LH", "order": index, "complexLabel": label, "typeName": "55B-1",
                                       "exclusiveArea": "", "supplyArea": "", "suppliedUnitCount": "20"}
                                      for index, label in enumerate(lh_labels)]}

    result = AnnouncementPdfExtractor(StageReplyModel({"data": {"rows": rows}, "sharedCounts": []}, notice)).extract(
        job(block_heading_tables_pdf(), job_input))

    tree = json.loads(result["json"])
    unconfirmed = {item["path"] for item in tree["data"]["unresolvedFields"]}
    assert ("supplyRows[0].complexReference.sourceComplexName" not in unconfirmed) is first_confirmed
    if first_confirmed:
        assert tree["data"]["supplyRows"][0]["complexReference"]["sourceComplexName"] == names[0]
    if names == ("광주선운2 A-1블록", "광주선운2 A-3블록"):
        # 두 블록의 55B-1이 공급행 하나로 합쳐지지 않고 따로 남는다.
        assert [row["complexReference"]["sourceComplexName"] for row in tree["data"]["supplyRows"]] == list(names)
