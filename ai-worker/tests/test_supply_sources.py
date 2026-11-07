import time
from concurrent.futures import ThreadPoolExecutor

from ai_worker import pages, supply_sources
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
