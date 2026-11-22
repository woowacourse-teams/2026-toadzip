from types import SimpleNamespace

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
    # 공급 단계는 공고문 표의 칸 단위(공급표 모양)로 답한다. 공통 수량을 행 번호로 따로 적지 않는다.
    assert all(f'"{name}"' in supply for name in ("housingTypes", "targets", "quotas", "leaseTerms"))
    assert "rowIndexes" not in supply and "subtypes" not in supply
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


def test_notice_prompt_says_whether_missing_candidates_mean_no_complex_column():
    def notice(**changes):
        base = dict(rental_rows=0, supply_text="", supply_pages=[2], supply_row_ids=frozenset(), supply_row_text={},
                    notice_text="1. 건설위치: 인천광역시 미추홀구 학익동 757", notice_pages=[1], complex_candidates=[],
                    table_structure_fallback=False, ambiguous_supply_tables=False)
        return stage_prompts.notice_prompt(pages.PromptPages(**{**base, **changes}))

    table = SimpleNamespace(columns={"housingType": 1})
    # 표의 열 역할을 읽었는데 단지명 열이 없으면 본문 건설위치에서 단지를 만들게 한다(지시문 [단지]).
    assert "[공급 표에서 읽은 단지명 후보]\n(후보 없음: 공급 표에 단지명 열이 없음)" in notice(supply_tables=[table])
    # 표를 읽지 못했으면 단지명 열이 없는지 알 수 없다.
    assert "(후보를 읽지 못함)" in notice(supply_tables=[table], table_structure_fallback=True)
    assert "(후보를 읽지 못함)" in notice()
    assert "(후보 없음: 공급 표에 단지명 열이 없음)" in stage_prompts._INSTRUCTIONS
