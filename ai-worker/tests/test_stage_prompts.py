from ai_worker import pages, stage_prompts
from ai_worker.eligibility_pages import EligibilityPages
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
    # 신청자격은 따로 부르는 신청자격 단계만 받는다. 본문·공급 단계에는 그 필드가 없다.
    eligibility = stage_prompts.eligibility_prompt(EligibilityPages([7, 9], ["자격 글", "소득표 글"], ["「가」군", "「나」군"], True))
    assert "[추출 단계: 신청자격]" in eligibility and "body text" not in eligibility and "table rows" not in eligibility
    # 신청자격 쪽은 그 단계 안의 첨부 순번으로 주고, 공급표의 신청자 묶음 이름은 쪽 글 앞에 둔다.
    assert eligibility.endswith("[공급표의 신청자 묶음]\n- 「가」군\n- 「나」군\n\n"
                                "[첨부 1쪽 / 원본 7쪽]\n자격 글\n\n[첨부 2쪽 / 원본 9쪽]\n소득표 글")
    assert "[공급표의 신청자 묶음]\n-" not in stage_prompts.eligibility_prompt(EligibilityPages([7], ["자격 글"], [], True))
    assert '"applicationConditions"' not in notice and '"eligibility"' not in notice and '"eligibility"' not in supply
    # LH 행은 번호 없는 참고 목록이다. 번호를 붙이면 모델이 줄마다 공급행을 만들거나 번호로 맞추려 한다.
    assert "\n- 단지= | 주택형=39 | 전용=39.95 | 공급=55.10 | 모집수=20\n" in supply
    assert "1. 주택형" not in supply and "lhSourceRow" not in supply
