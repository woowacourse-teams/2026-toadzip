package com.toadzip.backend.eligibility.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.eligibility.ProfileFixtures;
import com.toadzip.backend.eligibility.RuleSetFixtureBuilder;

import org.junit.jupiter.api.Test;

import java.util.Map;

class SelectionEvaluationTest {
    @Test
    void 순위_단계가_여러개면_일부_결과를_전체_순위로_요약하지_않는다() {
        var builder = fixture();
        long procedure = procedure(builder);
        long first = builder.step(procedure, 1, "RANK");
        long second = builder.step(procedure, 2, "RANK");
        builder.tier(first, 1, builder.numericGroup("RANK_TIER", "AGE_AT_REFERENCE", "GTE", 19));
        builder.tier(second, 1, builder.numericGroup("RANK_TIER", "AGE_AT_REFERENCE", "GTE", 100));
        option(builder, procedure);

        var result = evaluate(builder).options().getFirst();

        assertThat(result.rankTierMin()).isNull();
        assertThat(result.rankTierMax()).isNull();
        assertThat(result.selectionResults()).hasSize(2);
        assertThat(result.selectionResults().getFirst().rankTierMin()).isEqualTo(1);
        assertThat(result.selectionResults().getLast().rankTierMin()).isNull();
        assertThat(result.selectionResults().getLast().outcome())
                .isEqualTo(ConditionOutcome.NOT_SATISFIED);
    }

    @Test
    void 음수_최고배점의_적용여부를_모르면_감점부터_0점까지_보여준다() {
        var builder = fixture();
        long procedure = procedure(builder);
        long step = builder.step(procedure, 1, "SCORE");
        long unknown = builder.group("SCORE_BAND", "ALL", "구직 중");
        builder.condition(unknown, "IS_JOB_SEEKER", "IS_TRUE", null);
        builder.score(step, "감점", 0, new long[] {unknown}, new int[] {-5});
        option(builder, procedure);

        var result = evaluate(builder).options().getFirst();

        assertThat(result.scoreMin()).isEqualByComparingTo("-5");
        assertThat(result.scoreMax()).isEqualByComparingTo("0");
        assertThat(result.selectionResults().getFirst().missingFactCodes())
                .containsExactly(InputFact.IS_JOB_SEEKER);
    }

    private RuleSetFixtureBuilder fixture() {
        return new RuleSetFixtureBuilder("selection-test.pdf", 1, "ab".repeat(32), 100);
    }

    private long procedure(RuleSetFixtureBuilder builder) {
        return builder.add(
                "selectionProcedures",
                "labelAsWritten",
                "선정 절차",
                "evidenceId",
                builder.evidenceId());
    }

    private void option(RuleSetFixtureBuilder builder, long procedure) {
        long complex =
                builder.add(
                        "complexes",
                        "complexNameAsWritten",
                        "검증 단지",
                        "supplyMethod",
                        "CONSTRUCTED",
                        "evidenceId",
                        builder.evidenceId());
        long unit = builder.unit(complex, "26형", "HAPPY_HOUSING", "26.0");
        long root = builder.numericGroup("ELIGIBILITY", "AGE_AT_REFERENCE", "GTE", 19);
        builder.option(unit, builder.path("검증 경로", root, procedure));
    }

    private EligibilityResult evaluate(RuleSetFixtureBuilder builder) {
        return new EligibilityEvaluator()
                .evaluate(builder.build(), ProfileFixtures.a(), Map.of(), null);
    }
}
