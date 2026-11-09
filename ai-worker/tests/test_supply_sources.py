import time
from concurrent.futures import ThreadPoolExecutor

from ai_worker import pages, supply_sources
from ai_worker.source_frame import SourceSupplyRow
from conftest import FakeJudge, FakeModel, two_quantity_tables_pdf


def selected_pages(content: bytes, numbers: list[int]) -> pages.PromptPages:
    with pages.open_pdf(content) as document:
        return pages.prompt_pages(document, numbers, time.monotonic() + 60)


def test_jev_is_not_called_after_the_model_cutoff():
    judge, model = FakeJudge(), FakeModel()
    selected = selected_pages(two_quantity_tables_pdf(), [1, 2])

    with ThreadPoolExecutor(max_workers=1) as pool:
        source = supply_sources.SupplyPlan(model, judge, [], selected, [""]).start(
            time.monotonic() - 1, time.monotonic() + 60, pool)
        source.wait()

    assert judge.calls == []
    assert isinstance(source, supply_sources.ModelSupply)
    assert len(model.prompts) == 1 and "[추출 단계: 공급 표]" in model.prompts[0]


def test_lh_rows_do_not_force_the_model_stage_when_the_code_can_read_the_table():
    # LH 행은 참고 자료라 코드가 표를 읽을 수 있으면 공급 단계 모델을 부르지 않는다.
    judge, model = FakeJudge(), FakeModel()
    selected = selected_pages(two_quantity_tables_pdf(), [1, 2])
    lh_rows = [SourceSupplyRow("LH", "LH:P:SUPPLY:0", "Alpha", "26A(청년)", "26.73", "", "20")]

    with ThreadPoolExecutor(max_workers=1) as pool:
        source = supply_sources.SupplyPlan(model, judge, lh_rows, selected, [""]).start(
            time.monotonic() + 60, time.monotonic() + 60, pool)
        source.wait()

    assert isinstance(source, supply_sources.CodeSupply)
    assert model.prompts == [] and judge.calls
