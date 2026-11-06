import json
import re
import time
from types import SimpleNamespace
from uuid import uuid4

import pymupdf

import pytest

from ai_worker import announcement, checks, jev, pages, supply_grouping
from ai_worker.announcement import AnnouncementPdfExtractor, LhSupplyRow
from ai_worker.openai import Reply
from ai_worker import openai
from ai_worker.extraction import ATTEMPT_BUDGET, PermanentError
from ai_worker.jobs import Job
from conftest import contract_example
from test_pages import COVER, NOTICE, RENT, RENT_CELLS, SUPPLY, pdf as text_pdf

USAGE = {"promptTokens": 9000, "outputTokens": 3000, "thinkingTokens": 500}
EXTRACTION = {
    "data": {"announcement": {"name": "여수미평1 영구임대주택 예비입주자 모집"},
             "receptionPlace": {"name": None, "method": None, "address": None, "contact": None, "url": None}, "complexes": [],
             "supplyRows": [{"sourceHousingTypeName": "16A", "lhSourceRow": 1, "totalSupplyHouseholdCount": 78}], "schedules": []},
    "sharedCounts": [], "notApplicable": [],
    "evidence": [{"path": "supplyRows[0]", "page": 2, "quote": "공용 26.37 298"}],
}


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


class FakeModel:
    def __init__(self, content=json.dumps(EXTRACTION, ensure_ascii=False) + "\n뒤에 붙은 글", stage_contents=None):
        self.content = content
        self.stage_contents = stage_contents or {}
        self.prompts = []
        self.deadlines = []

    def chat(self, prompt, deadline):
        self.prompts.append(prompt)
        self.deadlines.append(deadline)
        stage = "supply" if "[추출 단계: 공급 표]" in prompt else "notice"
        if stage in self.stage_contents:
            expected = ({"complexes": list, "supplyRows": list} if stage == "supply" else {
                "announcement": dict, "receptionPlace": dict, "complexes": list,
                "schedules": list, "unresolvedFields": list,
            })
            tree = checks.first_json_object(self.stage_contents[stage], expected)
        else:
            tree = stage_output(checks.first_json_object(self.content), stage, prompt)
        if stage == "supply" and "rows" not in tree.get("data", {}):
            tree = compact_stage_output(tree, prompt)
        return Reply(json.dumps(tree, ensure_ascii=False) + "\n뒤에 붙은 글", USAGE)


def stage_output(tree, stage, prompt):
    data = tree["data"]
    supply = stage == "supply"
    fields = ("complexes", "supplyRows", "unresolvedFields") if supply else (
        "announcement", "receptionPlace", "complexes", "schedules", "unresolvedFields")
    result = {"data": {name: data.get(name, {} if name == "receptionPlace" else []) for name in fields}}
    result["data"]["unresolvedFields"] = [
        item for item in checks.objects(data.get("unresolvedFields"))
        if ((supply and item.get("path", "").startswith(("supplyRows[", "complexes[")))
            or (not supply and not item.get("path", "").startswith("supplyRows[")))
    ]
    root_fields = ("sharedCounts", "notApplicable", "evidence") if supply else ("notApplicable", "evidence")
    for name in root_fields:
        value = tree.get(name, [])
        if name in ("notApplicable", "evidence"):
            def belongs(item):
                path = item.get("path", "")
                is_supply_path = path.startswith("supplyRows[")
                return is_supply_path if supply else not is_supply_path
            value = [item for item in value if belongs(item)]
        result[name] = value
    if supply:
        row_ids = re.findall(r"\[(P\d+:T\d+:R\d+)\]", prompt)
        first_id = row_ids[:1]
        source_rows = []
        for index, row in enumerate(checks.objects(data.get("supplyRows"))):
            source_rows.append({"path": f"supplyRows[{index}]", "rowIds": first_id})
            for target_index, _ in enumerate(checks.objects(row.get("targets"))):
                source_rows.append({"path": f"supplyRows[{index}].targets[{target_index}]", "rowIds": first_id})
        result["sourceRows"] = source_rows
    return result


def compact_stage_output(tree, prompt):
    data = tree["data"]
    source_map = {item["path"]: item.get("rowIds", []) for item in tree.get("sourceRows", [])}
    row_ids = re.findall(r"\[(P\d+:T\d+:R\d+)\]", prompt)
    fallback_ids = row_ids[:1]
    rows = []
    base_fields = (
        "totalSupplyHouseholdCount", "exclusiveArea", "commonArea", "supplyArea", "constructedHouseholdCount",
        "expectedMoveInMonth", "lhSourceRow",
    )
    target_fields = (
        "supplyHouseholdCount", "reserveCount", "rentalDeposit", "monthlyRent", "convertedDeposit",
        "applicationCondition",
    )
    for index, row in enumerate(checks.objects(data.get("supplyRows"))):
        base = {"rowIds": source_map.get(f"supplyRows[{index}]", fallback_ids)}
        reference = row.get("complexReference")
        if isinstance(reference, dict):
            base["complexName"] = reference.get("sourceComplexName")
        base["housingType"] = row.get("sourceHousingTypeName")
        if row.get("subtypes"):
            base["subtypes"] = row["subtypes"]
        base.update({field: row[field] for field in base_fields if field in row and row[field] is not None})
        targets = checks.objects(row.get("targets"))
        if not targets:
            rows.append(base)
        for target_index, target in enumerate(targets):
            compact = dict(base)
            compact["rowIds"] = source_map.get(f"supplyRows[{index}].targets[{target_index}]", base["rowIds"])
            compact["target"] = target.get("target")
            compact.update({field: target[field] for field in target_fields
                            if field in target and target[field] is not None})
            rows.append(compact)
    return {"data": {"rows": rows}}


class FakeJudge:
    """Jev 대역. 기본은 머리글대로(예비 → 새 예비자 수, 그 밖 → 당첨자 전체) 높은 확률로 답한다.
    by_header로 특정 머리글에만 다른 뜻을 답하게 할 수 있다."""

    def __init__(self, choice=None, probability=0.95, error=None, by_header=None):
        self.choice, self.probability, self.error = choice, probability, error
        self.by_header = by_header or {}
        self.calls = []

    def count_meanings(self, state, headers, reserve_only, timeout=None):
        self.calls.append((state, headers, reserve_only))
        if self.error:
            raise self.error
        result = {}
        for key, header in headers.items():
            choice = self.by_header.get(header) or self.choice or (
                "new_reserve_this_notice" if "예비" in header else "winners_total_this_notice")
            if reserve_only and choice in jev.WINNER_OPTIONS:
                choice = "building_or_complex_size"  # 실제 Jev처럼 빠진 선택지는 답하지 않는다.
            result[key] = jev.Meaning(choice, self.probability)
        return result


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
    # LH 행 없이 모델이 쓴 LH 행 번호는 가리킬 LH 행이 없어 비운다.
    assert len(tree["data"]["supplyRows"]) == 1
    assert tree["data"]["supplyRows"][0]["sourceHousingTypeName"] is None
    assert tree["data"]["supplyRows"][0]["lhSourceRow"] is None
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


def test_lh_rows_are_sent_as_supply_row_frame_and_counts_are_checked():
    model = FakeModel()
    job_input = contract_example("input.example.json")

    result = AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT), job_input))

    prompt = stage_prompt(model, "supply")
    assert announcement.LH_FRAME in prompt
    assert "[LH 공급 API 행]\n1. 주택형=16A(대학생/청년) | 전용=16.37 | 공급=27.6384 | 모집수=78" in prompt
    assert any("LH API" in warning for warning in result["warnings"]) is False


def test_unverified_lh_count_is_cleared_before_comparison():
    job_input = {"lhSupplyRows": [{"typeName": "16A", "exclusiveArea": "16.37", "supplyArea": "27.6", "suppliedUnitCount": "80"}]}

    result = AnnouncementPdfExtractor(FakeModel()).extract(job(pdf(COVER, SUPPLY, RENT), job_input))

    # 구조화된 행 ID가 없으므로 이름과 수량을 추출 확정값으로 남기지 않는다.
    assert json.loads(result["json"])["data"]["supplyRows"][0]["totalSupplyHouseholdCount"] is None
    assert json.loads(result["json"])["data"]["supplyRows"][0]["sourceHousingTypeName"] is None
    assert "supplyRows[0].totalSupplyHouseholdCount" in {
        item["path"] for item in json.loads(result["json"])["data"]["unresolvedFields"]
    }
    assert not any("모집수 80와 다름" in warning for warning in result["warnings"])


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

    assert "[LH 공급 API 행]\n1. 주택형=51 | 전용= | 공급= | 모집수=240" in stage_prompt(model, "supply")
    rows = json.loads(result["json"])["data"]["supplyRows"]
    assert [(row["sourceHousingTypeName"], row["lhSourceRow"], row["totalSupplyHouseholdCount"]) for row in rows] == [
        (None, MERGED, None), (None, MERGED, None), (None, MERGED, None)]
    assert any("같은 의미의 원문 표 열에서 확인되지 않음" in item["reason"]
               for item in json.loads(result["json"])["data"]["unresolvedFields"])


def test_split_rows_without_supported_source_are_unresolved_before_lh_comparison():
    extraction = dict(EXTRACTION, data=dict(EXTRACTION["data"], supplyRows=[
        split_row("051.9800A", 200, 468), split_row("059.9100A", 25, 183)]), evidence=[])

    result = AnnouncementPdfExtractor(FakeModel(json.dumps(extraction, ensure_ascii=False))).extract(
        job(pdf(COVER, SUPPLY, RENT), MERGED_LH_INPUT))

    tree = json.loads(result["json"])
    assert [row["totalSupplyHouseholdCount"] for row in tree["data"]["supplyRows"]] == [None, None]
    assert {item["path"] for item in tree["data"]["unresolvedFields"]} >= {
        "supplyRows[0].totalSupplyHouseholdCount", "supplyRows[1].totalSupplyHouseholdCount",
    }
    assert not any("모집수 합계" in warning for warning in result["warnings"])


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
    assert (row["lhSourceRow"], row["totalSupplyHouseholdCount"], len(row["subtypes"])) == (MERGED, None, 2)
    assert "supplyRows[0].totalSupplyHouseholdCount" in {
        item["path"] for item in json.loads(result["json"])["data"]["unresolvedFields"]
    }
    assert not any("LH API" in warning for warning in result["warnings"])


def test_without_lh_rows_frame_is_not_sent():
    model = FakeModel()

    AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT), {"lhSupplyRows": []}))

    prompt = stage_prompt(model, "supply")
    assert announcement.LH_FRAME not in prompt
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


def test_input_example_parses_as_lh_rows():
    assert announcement.lh_rows(contract_example("input.example.json")) == [
        LhSupplyRow("16A(대학생/청년)", "16.37", "27.6384", "78", "LH:2015122300018207:SUPPLY:0"),
    ]


def test_model_deadline_counts_from_attempt_start():
    model = FakeModel()
    started = time.monotonic()

    AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT)))

    assert len(model.deadlines) == 2
    budget = ATTEMPT_BUDGET.total_seconds() - openai.READ_TIMEOUT
    assert all(started + budget - 5 < deadline <= time.monotonic() + budget for deadline in model.deadlines)


def test_slow_pdf_fails_before_paid_call(monkeypatch):
    monkeypatch.setattr(announcement, "MIN_MODEL_TIME", ATTEMPT_BUDGET.total_seconds() + 1)
    model = FakeModel()

    with pytest.raises(PermanentError) as error:
        AnnouncementPdfExtractor(model).extract(job(pdf(COVER, SUPPLY, RENT)))

    assert error.value.code == "PDF_TOO_SLOW"
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
    monkeypatch.setattr(pages, "detected_tables", lambda page: ([], False))
    model = FakeModel()

    with pytest.raises(PermanentError) as error:
        AnnouncementPdfExtractor(model).extract(job(text_pdf(COVER, SUPPLY, RENT)))

    assert error.value.code == announcement.SUPPLY_TABLE_UNSTRUCTURED
    assert "원본 PDF 2, 3쪽" in error.value.message
    assert model.prompts == []


def test_missing_targets_against_rent_table_is_warning_not_failure(monkeypatch):
    table = SimpleNamespace(to_markdown=lambda: "|표|", extract=lambda: RENT_CELLS)
    monkeypatch.setattr(pymupdf.Page, "find_tables", lambda page: SimpleNamespace(tables=[table]))
    monkeypatch.setattr(pages, "table_cells", lambda page, table: RENT_CELLS)

    result = AnnouncementPdfExtractor(FakeModel()).extract(job(pdf(COVER, SUPPLY, RENT)))

    assert "supplyRows: 공급대상 0개가 임대조건표 4줄보다 적어 빠진 대상이 있을 수 있음" in result["warnings"]


def test_stage_prompts_include_projected_schema_and_separate_inputs():
    rows = [LhSupplyRow("39", "39.95", "55.10", "20", "LH:P:SUPPLY:0"), LhSupplyRow("51A", "51.84", "71.0", "0", "LH:P:SUPPLY:1")]
    selected = pages.PromptPages(text="all", rental_rows=0, supply_text="table rows", supply_pages=[4],
                                 supply_row_ids=frozenset({"P4:T1:R2"}), supply_row_text={"P4:T1:R2": "row text"},
                                 notice_text="body text", notice_pages=[1],
                                 complex_candidates=["Complex 1"], table_structure_fallback=False,
                                 ambiguous_supply_tables=False)

    supply = announcement.build_supply_prompt(rows, selected)
    notice = announcement.build_notice_prompt(selected)

    assert "[추출 단계: 공급 표]" in supply and "[추출 단계: 공고 본문]" not in supply
    assert "[공급 표에서 읽은 단지명 후보]\n- Complex 1" in notice
    assert "[추출 단계: 공고 본문]" in notice and "table rows" not in notice
    assert '"rowIds"' in supply and '"supplyRows"' not in supply and '"sourceRows"' not in supply
    assert '"supplyRows"' not in notice
    assert "1. 주택형=39 | 전용=39.95 | 공급=55.10 | 모집수=20" in supply


def test_compact_supply_rows_are_assembled_from_cell_values_into_manager_contract():
    selected = pages.PromptPages(
        text="all", rental_rows=0, supply_text="table", supply_pages=[4],
        supply_row_ids=frozenset({"P4:T1:R4", "P4:T1:R5"}),
        supply_row_text={"P4:T1:R4": "Test\t26\t26A\t청년\t20", "P4:T1:R5": "Test\t26\t26A\t고령자\t3"},
        notice_text="body", notice_pages=[1], complex_candidates=["Test"], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={
            "P4:T1:R4": {"complexName": "Test", "housingType": "26A", "target": "청년", "reserveCount": "20",
                          "exclusiveArea": "26", "commonArea": "15", "totalHouseholdCount": "100"},
            "P4:T1:R5": {"complexName": "Test", "housingType": "26A", "target": "고령자", "reserveCount": "3",
                          "totalHouseholdCount": "100"},
        },
    )
    compact = {"data": {"rows": [
        {"rowIds": ["P4:T1:R4"], "complexName": "Test", "housingType": "26A", "target": "청년",
         "reserveCount": 20, "exclusiveArea": 26, "commonArea": 15},
        {"rowIds": ["P4:T1:R5"], "complexName": "Test", "housingType": "26A", "target": "고령자", "reserveCount": 3},
    ]}}

    tree = announcement.compact_supply_tree(compact, selected)

    assert len(tree["data"]["supplyRows"]) == 1
    row = tree["data"]["supplyRows"][0]
    assert row["complexReference"]["sourceComplexName"] == "Test"
    assert row["sourceHousingTypeName"] == "26A"
    assert [(target["target"], target["reserveCount"]) for target in row["targets"]] == [("청년", 20), ("고령자", 3)]
    assert tree["data"]["complexes"] == [{
        "sourceComplexName": "Test", "address": None, "totalHouseholdCount": 100, "parkingCount": None,
    }]
    assert {item["path"] for item in tree["evidence"]} >= {
        "complexes[0]", "supplyRows[0]", "supplyRows[0].targets[0]", "supplyRows[0].targets[1]",
    }


def test_compact_target_removes_extraction_whitespace_around_parentheses():
    selected = pages.PromptPages(
        text="all", rental_rows=0, supply_text="table", supply_pages=[5],
        supply_row_ids=frozenset({"P5:T1:R38"}),
        supply_row_text={"P5:T1:R38": "Sample Complex\t18A2\t청년 ( 주거약자)\t3"},
        notice_text="body", notice_pages=[1], complex_candidates=["Sample Complex"],
        table_structure_fallback=False, ambiguous_supply_tables=False,
        supply_row_values={"P5:T1:R38": {
            "complexName": "Sample Complex", "housingType": "18A2", "target": "청년 ( 주거약자)",
            "reserveCount": "3",
        }},
    )
    compact = {"data": {"rows": [{
        "rowIds": ["P5:T1:R38"], "complexName": "Sample Complex", "housingType": "18A2",
        "target": "청년 ( 주거약자)", "reserveCount": 3,
    }]}}

    tree = announcement.compact_supply_tree(compact, selected)
    row = tree["data"]["supplyRows"][0]
    warnings = []
    checks.check_supply_row(row, "supplyRows[0]", "", set(), warnings)

    assert row["targets"][0]["target"] == "청년(주거약자)"
    assert not any("target: 표기 규칙 위반" in warning for warning in warnings)


def test_compact_supply_text_that_disagrees_with_source_cells_becomes_unresolved():
    selected = pages.PromptPages(
        text="all", rental_rows=0, supply_text="table", supply_pages=[4],
        supply_row_ids=frozenset({"P4:T1:R4"}), supply_row_text={"P4:T1:R4": "Test\t26A\t청년\t20"},
        notice_text="body", notice_pages=[1], complex_candidates=["Test"], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={"P4:T1:R4": {"complexName": "Test", "housingType": "26A", "target": "청년",
                                         "reserveCount": "20"}},
    )
    compact = {"data": {"rows": [{"rowIds": ["P4:T1:R4"], "complexName": "Fake", "housingType": "26B",
                                   "target": "고령자", "reserveCount": 99}]}}

    tree = announcement.compact_supply_tree(compact, selected)

    row = tree["data"]["supplyRows"][0]
    assert row["complexReference"]["sourceComplexName"] == ""
    assert row["sourceHousingTypeName"] is None
    assert row["targets"][0]["target"] == ""
    assert row["targets"][0]["reserveCount"] is None
    assert {item["path"] for item in tree["data"]["unresolvedFields"]} >= {
        "supplyRows[0].complexReference.sourceComplexName", "supplyRows[0].sourceHousingTypeName",
        "supplyRows[0].targets[0].target", "supplyRows[0].targets[0].reserveCount",
    }


def test_compact_shared_count_is_mapped_to_each_target_without_copying_the_value():
    selected = pages.PromptPages(
        text="all", rental_rows=0, supply_text="table", supply_pages=[4],
        supply_row_ids=frozenset({"P4:T1:R4", "P4:T1:R5", "P4:T1:R6"}),
        supply_row_text={
            "P4:T1:R4": "Test\t26A\t청년", "P4:T1:R5": "Test\t26A\t고령자",
            "P4:T1:R6": "공통 예비입주자 모집수\t20",
        },
        notice_text="body", notice_pages=[1], complex_candidates=["Test"], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={
            "P4:T1:R4": {"complexName": "Test", "housingType": "26A", "target": "청년"},
            "P4:T1:R5": {"complexName": "Test", "housingType": "26A", "target": "고령자"},
            "P4:T1:R6": {"reserveCount": "20"},
        },
    )
    compact = {
        "data": {"rows": [
            {"rowIds": ["P4:T1:R4"], "complexName": "Test", "housingType": "26A", "target": "청년"},
            {"rowIds": ["P4:T1:R5"], "complexName": "Test", "housingType": "26A", "target": "고령자"},
        ]},
        "sharedCounts": [{"rowIndexes": [0, 1], "field": "reserveCount", "count": 20,
                          "rowIds": ["P4:T1:R6"]}],
    }

    tree = announcement.compact_supply_tree(compact, selected)

    assert [target["reserveCount"] for target in tree["data"]["supplyRows"][0]["targets"]] == [None, None]
    assert tree["sharedCounts"] == [{
        "paths": ["supplyRows[0].targets[0].reserveCount", "supplyRows[0].targets[1].reserveCount"],
        "count": 20, "page": 4, "quote": "공통 예비입주자 모집수\t20",
    }]
    assert not any(item["path"].endswith("reserveCount") for item in tree["data"]["unresolvedFields"])


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
                "lhSourceRow": None, "expectedMoveInMonth": None, "totalSupplyHouseholdCount": 20,
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
    supply["data"]["supplyRows"][0]["lhSourceRow"] = 1
    notice = {
        "data": {
            "announcement": {"name": "Sample Notice", "postedDate": None, "applicationStartDate": None,
                             "applicationEndDate": None, "winnerAnnouncementDate": None},
            "receptionPlace": {"name": None, "method": None, "address": None, "contact": None, "url": None},
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

    job_input = {"lhSupplyRows": [{"typeName": "26A", "exclusiveArea": "26", "supplyArea": "", "suppliedUnitCount": "80"}]}
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
    assert "supplyRows[0].totalSupplyHouseholdCount: LH API 공급행 1 모집수 80와 다름(20)" in result["warnings"]
    assert not any("원문 표 행 ID" in warning or "지정한 원문 표 행" in warning for warning in result["warnings"])


def test_supply_table_the_code_can_read_skips_the_supply_model_call():
    notice = {
        "data": {
            "announcement": {"name": "Sample Notice"}, "receptionPlace": {},
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


def grid_page(document, rows):
    page = document.new_page()
    xs = [20, 110, 200, 290, 380, 470, 575]
    ys = [48 + 30 * index for index in range(len(rows) + 1)]
    for x in xs:
        page.draw_line((x, ys[0]), (x, ys[-1]))
    for y in ys:
        page.draw_line((xs[0], y), (xs[-1], y))
    for row_index, row in enumerate(rows):
        for column, value in enumerate(row):
            page.insert_text((xs[column] + 2, ys[row_index] + 18), value, fontname="korea", fontsize=7)
    return page


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


def test_complex_paths_are_remapped_before_stage_evidence_is_combined():
    supply = {
        "data": {
            "announcement": {}, "receptionPlace": {}, "schedules": [], "unresolvedFields": [
                {"path": "complexes[0].parkingCount", "reason": "공급 단계 확인 필요"}],
            "complexes": [{"sourceComplexName": "Alpha", "totalHouseholdCount": 100, "parkingCount": None}],
            "supplyRows": [{"complexReference": {"sourceComplexName": "Alpha"}, "targets": []}],
        },
        "sharedCounts": [],
        "notApplicable": [{"path": "complexes[0].address", "reason": "공급 단계 주소 없음"}],
        "evidence": [{"path": "complexes[0]", "page": 1, "quote": "Alpha"}],
        "sourceRows": [],
    }
    notice = {
        "data": {
            "announcement": {"name": "Sample"}, "receptionPlace": {}, "schedules": [], "unresolvedFields": [
                {"path": "complexes[0].parkingCount", "reason": "본문 확인 필요"}],
            "complexes": [
                {"sourceComplexName": "Beta", "address": "Beta Road", "totalHouseholdCount": 50, "parkingCount": 10},
                {"sourceComplexName": "Alpha", "address": "Alpha Road", "totalHouseholdCount": 100, "parkingCount": 20},
            ],
        },
        "notApplicable": [],
        "evidence": [
            {"path": "complexes[0]", "page": 1, "quote": "Beta"},
            {"path": "complexes[1]", "page": 1, "quote": "Alpha"},
        ],
    }

    tree, _ = announcement.merge_stages(supply, notice, {})

    assert tree["data"]["complexes"][0]["sourceComplexName"] == "Alpha"
    assert tree["data"]["complexes"][1]["sourceComplexName"] == "Beta"
    assert {(item["path"], item["quote"]) for item in tree["evidence"]} == {
        ("complexes[0]", "Alpha"), ("complexes[1]", "Beta"),
    }
    assert {item["path"] for item in tree["notApplicable"]} == {"complexes[0].address"}


def test_missing_complex_values_are_reported_as_unresolved():
    supply = {
        "data": {
            "complexes": [{"sourceComplexName": "Alpha", "totalHouseholdCount": None, "parkingCount": None}],
            "supplyRows": [{"complexReference": {"sourceComplexName": "Alpha"}, "targets": []}],
            "unresolvedFields": [],
        },
        "sharedCounts": [], "notApplicable": [], "evidence": [], "sourceRows": [],
    }
    notice = {
        "data": {"announcement": {}, "receptionPlace": {}, "complexes": [], "schedules": [], "unresolvedFields": []},
        "notApplicable": [], "evidence": [],
    }

    tree, _ = announcement.merge_stages(supply, notice, {})

    assert {item["path"] for item in tree["data"]["unresolvedFields"]} >= {
        "complexes[0].address", "complexes[0].totalHouseholdCount", "complexes[0].parkingCount",
    }


def test_supply_count_not_present_in_referenced_source_row_is_cleared_and_unresolved():
    rows = [{"sourceHousingTypeName": "26A", "targets": [{"target": "청년", "reserveCount": 99}]}]
    source_rows = {"supplyRows[0]": {"P1:T1:R2"}, "supplyRows[0].targets[0]": {"P1:T1:R2"}}
    source_text = {"P1:T1:R2": "Test\t26A\t청년\t20"}
    source_values = {"P1:T1:R2": {"complexName": "Test", "housingType": "26A", "target": "청년", "reserveCount": "20"}}
    evidence = [
        {"path": "supplyRows[0]", "page": 1, "quote": "Test 26A"},
        {"path": "supplyRows[0].targets[0]", "page": 1, "quote": "26A 청년"},
    ]
    unresolved = []

    warnings = announcement.check_source_rows(rows, source_rows, source_text, evidence, unresolved, source_values)

    assert rows[0]["targets"][0]["reserveCount"] is None
    assert {item["path"] for item in unresolved} == {"supplyRows[0].targets[0].reserveCount"}
    assert any("reserveCount" in warning for warning in warnings)


def test_supply_count_matching_a_different_source_cell_is_cleared_and_unresolved():
    rows = [{"sourceHousingTypeName": "26A", "targets": [{"target": "청년", "reserveCount": 26}]}]
    source_rows = {"supplyRows[0]": {"P1:T1:R2"}, "supplyRows[0].targets[0]": {"P1:T1:R2"}}
    source_text = {"P1:T1:R2": "Test\t26A\t청년\t20"}
    source_values = {"P1:T1:R2": {"complexName": "Test", "housingType": "26A", "target": "청년", "reserveCount": "20"}}
    evidence = [
        {"path": "supplyRows[0]", "page": 1, "quote": "Test 26A"},
        {"path": "supplyRows[0].targets[0]", "page": 1, "quote": "26A 청년 20"},
    ]
    unresolved = []

    warnings = announcement.check_source_rows(rows, source_rows, source_text, evidence, unresolved, source_values)

    assert rows[0]["targets"][0]["reserveCount"] is None
    assert "supplyRows[0].targets[0].reserveCount" in {item["path"] for item in unresolved}
    assert any("reserveCount" in warning for warning in warnings)


def test_non_numeric_supply_values_must_match_their_meaningful_source_cells():
    rows = [{"complexReference": {"sourceComplexName": "Fake"}, "sourceHousingTypeName": "26B",
             "targets": [{"target": "고령자", "reserveCount": 20}]}]
    row_ids = {"P1:T1:R2"}
    source_rows = {"supplyRows[0]": row_ids, "supplyRows[0].targets[0]": row_ids}
    source_text = {"P1:T1:R2": "Test\t26A\t청년\t20"}
    source_values = {"P1:T1:R2": {"complexName": "Test", "housingType": "26A", "target": "청년", "reserveCount": "20"}}
    evidence = [
        {"path": "supplyRows[0]", "page": 1, "quote": "Test 26A"},
        {"path": "supplyRows[0].targets[0]", "page": 1, "quote": "청년 20"},
    ]
    unresolved = []

    announcement.check_source_rows(rows, source_rows, source_text, evidence, unresolved, source_values)

    assert rows[0]["complexReference"]["sourceComplexName"] == ""
    assert rows[0]["sourceHousingTypeName"] is None
    assert rows[0]["targets"][0]["target"] == ""
    assert {item["path"] for item in unresolved} >= {
        "supplyRows[0].complexReference.sourceComplexName", "supplyRows[0].sourceHousingTypeName",
        "supplyRows[0].targets[0].target",
    }


def test_allowed_target_group_notation_normalization_is_preserved():
    rows = [{"complexReference": {"sourceComplexName": "Test"}, "sourceHousingTypeName": "26A",
             "targets": [{"target": "가군", "reserveCount": 20}]}]
    row_ids = {"P1:T1:R2"}
    source_rows = {"supplyRows[0]": row_ids, "supplyRows[0].targets[0]": row_ids}
    source_text = {"P1:T1:R2": "Test\t26A\t「가」군\t20"}
    source_values = {"P1:T1:R2": {
        "complexName": "Test", "housingType": "26A", "target": "「가」군", "reserveCount": "20",
    }}
    evidence = [
        {"path": "supplyRows[0]", "page": 1, "quote": "Test 26A"},
        {"path": "supplyRows[0].targets[0]", "page": 1, "quote": "「가」군 20"},
    ]
    unresolved = []

    announcement.check_source_rows(rows, source_rows, source_text, evidence, unresolved, source_values)

    assert rows[0]["targets"][0]["target"] == "가군"
    assert "supplyRows[0].targets[0].target" not in {item["path"] for item in unresolved}


def test_unsupported_target_evidence_clears_numeric_values_and_marks_each_field_unresolved():
    rows = [{"targets": [{"target": "청년", "reserveCount": 20, "rentalDeposit": 46920000}]}]
    unresolved = []

    announcement.check_source_rows(rows, {}, {}, [], unresolved)

    assert rows[0]["targets"][0]["reserveCount"] is None
    assert rows[0]["targets"][0]["rentalDeposit"] is None
    assert {item["path"] for item in unresolved} >= {
        "supplyRows[0].targets[0]", "supplyRows[0].targets[0].reserveCount", "supplyRows[0].targets[0].rentalDeposit",
    }


def test_three_conflicting_complex_values_remain_unresolved():
    complexes = [
        {"sourceComplexName": "Alpha", "address": "Road", "totalHouseholdCount": None, "parkingCount": None},
        {"sourceComplexName": "Alpha", "address": "Other Road", "totalHouseholdCount": None, "parkingCount": None},
        {"sourceComplexName": "Alpha", "address": "Third Road", "totalHouseholdCount": None, "parkingCount": None},
    ]

    merged, _, _, unresolved, _ = announcement.merge_complexes([], [], complexes)

    assert merged[0]["address"] is None
    assert {item["path"] for item in unresolved} == {"complexes[0].address"}


def test_model_target_with_income_split_column_is_accepted_as_the_combined_source_name():
    selected = pages.PromptPages(
        text="all", rental_rows=0, supply_text="table", supply_pages=[8],
        supply_row_ids=frozenset({"P8:T1:R3"}), supply_row_text={"P8:T1:R3": "Test\t24A\t청년\t소득 없음\t46,920"},
        notice_text="body", notice_pages=[1], complex_candidates=["Test"], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={"P8:T1:R3": {"complexName": "Test", "housingType": "24A", "target": "청년",
                                         "targetDetail": "소득 없음"}},
    )
    compact = {"data": {"rows": [
        {"rowIds": ["P8:T1:R3"], "complexName": "Test", "housingType": "24A", "target": "청년(소득 없음)"},
        {"rowIds": ["P8:T1:R3"], "complexName": "Test", "housingType": "24A", "target": "청년(소득 있음)"},
    ]}}

    tree = announcement.compact_supply_tree(compact, selected)

    assert [target["target"] for target in tree["data"]["supplyRows"][0]["targets"]] == ["청년(소득 없음)", ""]
    assert "supplyRows[0].targets[1].target" in {item["path"] for item in tree["data"]["unresolvedFields"]}


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


def two_quantity_tables_pdf(title="Sample Notice"):
    document = pymupdf.open()
    page = grid_page(document, [
        ["단지명", "주택형", "공급대상", "당첨자 수", "주거전용", "주거공용"],
        ["Alpha", "26A", "청년", "20", "26.73", "13.07"],
    ])
    page.insert_text((20, 30), title, fontname="korea", fontsize=8)
    grid_page(document, [
        ["단지명", "주택형", "공급대상", "모집호수 예비자", "주거전용", "주거공용"],
        ["Beta", "36A", "고령자", "3", "36.92", "18.06"],
    ])
    return document.tobytes()


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


def test_jev_is_not_called_after_the_model_cutoff():
    judge = FakeJudge()
    with pages.open_pdf(two_quantity_tables_pdf()) as document:
        selected = pages.prompt_pages(document, [1, 2], time.monotonic() + 60)
    grouped = supply_grouping.group(selected.supply_tables, selected.supply_row_values, [])

    assert AnnouncementPdfExtractor(FakeModel(), judge).counts_confirmed(grouped, [""], time.monotonic() - 1) is False
    assert judge.calls == []


def test_each_value_gets_evidence_from_the_row_that_holds_its_cell():
    selected = pages.PromptPages(
        text="all", rental_rows=0, supply_text="table", supply_pages=[4, 13],
        supply_row_ids=frozenset({"P4:T1:R4", "P13:T1:R3"}),
        supply_row_text={"P4:T1:R4": "Test\t26A\t청년\t20\t26.73", "P13:T1:R3": "Test\t26A\t청년\t46,920\t178,670"},
        notice_text="body", notice_pages=[1], complex_candidates=["Test"], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={
            "P4:T1:R4": {"complexName": "Test", "housingType": "26A", "target": "청년", "reserveCount": "20",
                          "exclusiveArea": "26.73"},
            "P13:T1:R3": {"complexName": "Test", "housingType": "26A", "target": "청년", "rentalDeposit": "46,920",
                           "rentalDepositUnit": "천원", "monthlyRent": "178,670", "monthlyRentUnit": "원"},
        },
    )
    compact = {"data": {"rows": [{
        "rowIds": ["P4:T1:R4", "P13:T1:R3"], "complexName": "Test", "housingType": "26A", "target": "청년",
        "reserveCount": 20, "exclusiveArea": 26.73, "rentalDeposit": 46920000, "monthlyRent": 178670,
    }]}}

    tree = announcement.compact_supply_tree(compact, selected)

    pages_by_path = {item["path"]: item["page"] for item in tree["evidence"]}
    # 공급행·대상 근거가 두 쪽에 걸쳐도 값마다 그 칸이 있는 행의 쪽을 가리킨다(13쪽이 4쪽보다 글자순으로 앞서도).
    assert pages_by_path["supplyRows[0].exclusiveArea"] == 4
    assert pages_by_path["supplyRows[0].targets[0].reserveCount"] == 4
    assert pages_by_path["supplyRows[0].targets[0].rentalDeposit"] == 13
    assert pages_by_path["supplyRows[0].targets[0].monthlyRent"] == 13


def test_notice_note_path_written_with_empty_brackets_reaches_the_result_readable():
    notice = {
        "data": {
            "announcement": {"name": "Sample Notice"}, "receptionPlace": {}, "complexes": [], "schedules": [],
            "unresolvedFields": [{"path": "complexes[].parkingCount", "reason": "주차대수를 확인하지 못함"}],
        },
        "notApplicable": [], "evidence": [],
    }
    model = FakeModel(stage_contents={"notice": json.dumps(notice, ensure_ascii=False)})

    result = AnnouncementPdfExtractor(model, FakeJudge()).extract(job(structured_table_pdf()))

    paths = [item["path"] for item in json.loads(result["json"])["data"]["unresolvedFields"]]
    assert "complexes[*].parkingCount" in paths and "complexes[].parkingCount" not in paths


def test_value_evidence_prefers_the_row_whose_cell_matches_the_value():
    selected = pages.PromptPages(
        text="all", rental_rows=0, supply_text="table", supply_pages=[8, 9],
        supply_row_ids=frozenset({"P8:T1:R3", "P9:T1:R3"}),
        supply_row_text={"P8:T1:R3": "Test\t26A\t청년\t소득 없음\t46,920", "P9:T1:R3": "\t\t\t소득 있음\t49,680"},
        notice_text="body", notice_pages=[1], complex_candidates=["Test"], table_structure_fallback=False,
        ambiguous_supply_tables=False,
        supply_row_values={
            "P8:T1:R3": {"complexName": "Test", "housingType": "26A", "target": "청년", "targetDetail": "소득 없음",
                          "rentalDeposit": "46,920", "rentalDepositUnit": "천원"},
            "P9:T1:R3": {"targetDetail": "소득 있음", "rentalDeposit": "49,680", "rentalDepositUnit": "천원"},
        },
    )
    compact = {"data": {"rows": [{"rowIds": ["P8:T1:R3", "P9:T1:R3"], "complexName": "Test", "housingType": "26A",
                                   "target": "청년(소득 있음)", "rentalDeposit": 49680000}]}}

    tree = announcement.compact_supply_tree(compact, selected)

    evidence = next(item for item in tree["evidence"] if item["path"] == "supplyRows[0].targets[0].rentalDeposit")
    assert evidence["page"] == 9 and "49,680" in evidence["quote"]
