from ai_worker import pages, stage_prompts
from ai_worker.source_frame import SourceSupplyRow


def test_stage_prompts_include_projected_schema_and_separate_inputs():
    rows = [SourceSupplyRow("LH", "LH:P:SUPPLY:0", "", "39", "39.95", "55.10", "20"),
            SourceSupplyRow("LH", "LH:P:SUPPLY:1", "", "51A", "51.84", "71.0", "0")]
    selected = pages.PromptPages(rental_rows=0, supply_text="table rows", supply_pages=[4],
                                 supply_row_ids=frozenset({"P4:T1:R2"}), supply_row_text={"P4:T1:R2": "row text"},
                                 notice_text="body text", notice_pages=[1],
                                 complex_candidates=["Complex 1"], table_structure_fallback=False,
                                 ambiguous_supply_tables=False)

    supply = stage_prompts.supply_prompt(rows, selected)
    notice = stage_prompts.notice_prompt(selected)

    assert "[추출 단계: 공급 표]" in supply and "[추출 단계: 공고 본문]" not in supply
    assert "[공급 표에서 읽은 단지명 후보]\n- Complex 1" in notice
    assert "[추출 단계: 공고 본문]" in notice and "table rows" not in notice
    assert '"rowIds"' in supply and '"supplyRows"' not in supply and '"sourceRows"' not in supply
    assert '"supplyRows"' not in notice
    assert "1. 주택형=39 | 전용=39.95 | 공급=55.10 | 모집수=20" in supply
