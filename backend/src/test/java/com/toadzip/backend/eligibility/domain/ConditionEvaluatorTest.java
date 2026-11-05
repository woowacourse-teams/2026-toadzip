package com.toadzip.backend.eligibility.domain;

import static com.toadzip.backend.eligibility.domain.ConditionOutcome.*;
import static com.toadzip.backend.eligibility.domain.InputFact.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.eligibility.ProfileFixtures;
import com.toadzip.backend.eligibility.RuleSetFixtureBuilder;
import com.toadzip.backend.eligibility.exception.InvalidEligibilityException;

import org.junit.jupiter.api.Test;

import java.util.Set;

class ConditionEvaluatorTest {
    private static final RegionMembership UNUSED_REGIONS =
            new RegionMembership() {
                @Override
                public Match match(Set<String> members, String region) {
                    return Match.INSUFFICIENT_PRECISION;
                }

                @Override
                public boolean isKnown(String code) {
                    return true;
                }
            };

    @Test
    void 가족구성원수가_1이어도_세대주로_추정하지_않는다() {
        var builder = fixture();
        long group = builder.group("ELIGIBILITY", "ALL", "대상에 따른 자산");
        long condition =
                builder.condition(
                        group,
                        "HOUSEHOLD_ASSETS",
                        "LTE",
                        100,
                        "subjectCode",
                        "HOUSEHOLD_IF_HEAD_ELSE_APPLICANT");
        var profile =
                ProfileFixtures.with(
                        ProfileFixtures.with(
                                ProfileFixtures.with(ProfileFixtures.a(), FAMILY_MEMBER_COUNT, 1),
                                APPLICANT_ASSETS,
                                50),
                        HOUSEHOLD_ASSETS,
                        200);
        Judgment result = evaluator(builder, profile).condition(condition);
        assertThat(result.outcome()).isEqualTo(UNDETERMINED);
        assertThat(result.reasons()).containsExactly(UndeterminedReason.SUBJECT_AMBIGUOUS);
        assertThat(result.shownValues()).containsKey("subjectCandidates");
    }

    @Test
    void 본인_후보의_1인_기준을_기준표와_가산_발동에_함께_적용한다() {
        var builder = fixture();
        long group = builder.group("ELIGIBILITY", "ALL", "소득");
        long condition =
                builder.condition(
                        group,
                        "HOUSEHOLD_MONTHLY_INCOME",
                        "LTE",
                        100,
                        "subjectCode",
                        "HOUSEHOLD_IF_HEAD_ELSE_APPLICANT",
                        "incomeStandardTableId",
                        1,
                        "householdSizeFactCode",
                        "FAMILY_MEMBER_COUNT",
                        "valueUnit",
                        "PERCENT_OF_STANDARD");
        long trigger = builder.numericGroup("ADJUSTMENT_TRIGGER", "FAMILY_MEMBER_COUNT", "EQ", 1);
        builder.adjustment(condition, trigger, "ADD_PERCENT_POINTS", 20, "SUM");
        var profile =
                ProfileFixtures.with(
                        ProfileFixtures.with(
                                ProfileFixtures.a(), APPLICANT_MONTHLY_INCOME, 4500000),
                        HOUSEHOLD_MONTHLY_INCOME,
                        8000000);
        assertThat(evaluator(builder, profile).condition(condition).outcome()).isEqualTo(SATISFIED);
    }

    @Test
    void 면제_여부가_보류일_때_기본조건의_충족은_유지하고_미충족은_보류한다() {
        var builder = fixture();
        long exemption = builder.group("EXEMPTION_TRIGGER", "ALL", "면제");
        builder.condition(exemption, "IS_JOB_SEEKER", "IS_TRUE", null);
        long group = builder.group("ELIGIBILITY", "ALL", "자산");
        long condition =
                builder.condition(
                        group, "APPLICANT_ASSETS", "LTE", 100, "exemptionGroupId", exemption);
        var passes = ProfileFixtures.with(ProfileFixtures.a(), APPLICANT_ASSETS, 50);
        var fails = ProfileFixtures.with(ProfileFixtures.a(), APPLICANT_ASSETS, 200);
        assertThat(evaluator(builder, passes).condition(condition).outcome()).isEqualTo(SATISFIED);
        var unknown = evaluator(builder, fails).condition(condition);
        assertThat(unknown.outcome()).isEqualTo(UNDETERMINED);
        assertThat(unknown.missingFacts()).containsExactly(IS_JOB_SEEKER);
        assertThat(unknown.blockers()).containsExactly(condition);
        assertThat(
                        evaluator(builder, ProfileFixtures.with(fails, IS_JOB_SEEKER, true))
                                .condition(condition)
                                .outcome())
                .isEqualTo(EXEMPT);
    }

    @Test
    void 가산_발동이_미입력이면_가능한_모든_한도에서_같은_결과일때만_확정한다() {
        var builder = fixture();
        long group = builder.group("ELIGIBILITY", "ALL", "자산");
        long condition = builder.condition(group, "APPLICANT_ASSETS", "LTE", 100);
        long trigger = builder.group("ADJUSTMENT_TRIGGER", "ALL", "구직 중");
        builder.condition(trigger, "IS_JOB_SEEKER", "IS_TRUE", null);
        builder.adjustment(condition, trigger, "REPLACE_NUMBER", 200, "MAX");
        assertThat(
                        evaluator(
                                        builder,
                                        ProfileFixtures.with(
                                                ProfileFixtures.a(), APPLICANT_ASSETS, 50))
                                .condition(condition)
                                .outcome())
                .isEqualTo(SATISFIED);
        assertThat(
                        evaluator(
                                        builder,
                                        ProfileFixtures.with(
                                                ProfileFixtures.a(), APPLICANT_ASSETS, 250))
                                .condition(condition)
                                .outcome())
                .isEqualTo(NOT_SATISFIED);
        Judgment between =
                evaluator(builder, ProfileFixtures.with(ProfileFixtures.a(), APPLICANT_ASSETS, 150))
                        .condition(condition);
        assertThat(between.outcome()).isEqualTo(UNDETERMINED);
        assertThat(between.missingFacts()).containsExactly(IS_JOB_SEEKER);
    }

    @Test
    void 참조_묶음_배제는_한번만_부정하고_이행조건은_입주전_안내로_분리한다() {
        var builder = fixture();
        long base = builder.numericGroup("RENT_CLASS", "APPLICANT_ASSETS", "LTE", 100);
        long inverse = builder.group("RENT_CLASS", "ALL", "가군 이외");
        long exclusion =
                builder.condition(
                        inverse,
                        null,
                        null,
                        null,
                        "conditionRole",
                        "EXCLUSION",
                        "referencedGroupId",
                        base,
                        "subjectCode",
                        "NONE");
        long obligation =
                builder.condition(
                        inverse,
                        "HAS_SAVINGS_ACCOUNT",
                        "IS_TRUE",
                        null,
                        "conditionRole",
                        "OBLIGATION",
                        "holdingPeriod",
                        "BY_MOVE_IN");
        var evaluation =
                evaluator(builder, ProfileFixtures.with(ProfileFixtures.b(), APPLICANT_ASSETS, 50));
        assertThat(evaluation.condition(exclusion).outcome()).isEqualTo(NOT_SATISFIED);
        assertThat(evaluation.condition(obligation).outcome()).isEqualTo(OBLIGATION_PENDING);
        assertThat(evaluation.condition(obligation).reasons()).isEmpty();
    }

    @Test
    void 미래세대의_요건은_현재세대_값으로_통과시키지_않는다() {
        var builder = fixture();
        long group = builder.group("ELIGIBILITY", "ALL", "혼인 후 세대");
        long condition =
                builder.condition(
                        group,
                        "HOUSEHOLD_HAS_HOUSE_OWNER",
                        "IS_FALSE",
                        null,
                        "subjectCode",
                        "FUTURE_HOUSEHOLD");
        assertThat(evaluator(builder, ProfileFixtures.b()).condition(condition).reasons())
                .containsExactly(UndeterminedReason.NOT_IN_INPUT_LIST);
    }

    @Test
    void 면제와_가산을_포함한_순환_참조는_공개전에_거부한다() {
        var builder = fixture();
        long group = builder.group("ELIGIBILITY", "ALL", "순환");
        long exemption = builder.group("EXEMPTION_TRIGGER", "ALL", "면제");
        builder.condition(group, "APPLICANT_ASSETS", "LTE", 100, "exemptionGroupId", exemption);
        builder.reference(exemption, group);
        assertThatThrownBy(
                        () ->
                                new RuleSetValidator(UNUSED_REGIONS, Set.of(1L))
                                        .validateForPublication(builder.build()))
                .isInstanceOf(InvalidEligibilityException.class)
                .satisfies(
                        error ->
                                assertThat(((InvalidEligibilityException) error).violations())
                                        .anyMatch(message -> message.contains("순환")));
    }

    @Test
    void 금액_반올림과_불확실_구간의_등호_경계를_보존한다() {
        assertThat(EligibilityAcceptanceTest.STANDARDS.get(1L).amount(9))
                .contains(new java.math.BigDecimal("11644097"));
        assertThat(
                        NumericComparison.compare(
                                "LTE",
                                new java.math.BigDecimal("100"),
                                new java.math.BigDecimal("100"),
                                new java.math.BigDecimal("100"),
                                new java.math.BigDecimal("200"),
                                null))
                .isEqualTo(SATISFIED);
        assertThat(
                        NumericComparison.compare(
                                "EQ",
                                new java.math.BigDecimal("100"),
                                new java.math.BigDecimal("101"),
                                new java.math.BigDecimal("100"),
                                new java.math.BigDecimal("100"),
                                null))
                .isEqualTo(UNDETERMINED);
    }

    private RuleSetFixtureBuilder fixture() {
        return new RuleSetFixtureBuilder("unit-test.pdf", 10, "ef".repeat(32), 100);
    }

    @Test
    void 확정된_대체한도가_있어도_다른_미확정_대체한도를_버리지_않는다() {
        var builder = fixture();
        long group = builder.group("ELIGIBILITY", "ALL", "자산");
        long condition = builder.condition(group, "APPLICANT_ASSETS", "LTE", 100);
        long known = builder.numericGroup("ADJUSTMENT_TRIGGER", "AGE_AT_REFERENCE", "GTE", 19);
        long unknown = builder.group("ADJUSTMENT_TRIGGER", "ALL", "구직 중");
        builder.condition(unknown, "IS_JOB_SEEKER", "IS_TRUE", null);
        builder.adjustment(condition, known, "REPLACE_NUMBER", 150, "MAX");
        builder.adjustment(condition, unknown, "REPLACE_NUMBER", 200, "MAX");
        Judgment result =
                evaluator(builder, ProfileFixtures.with(ProfileFixtures.a(), APPLICANT_ASSETS, 175))
                        .condition(condition);
        assertThat(result.outcome()).isEqualTo(UNDETERMINED);
        assertThat(result.missingFacts()).containsExactly(IS_JOB_SEEKER);
    }

    @Test
    void 미래_시점이_미확정인_배제조건을_확인사항만_남은_경로로_분류하지_않는다() {
        var builder = fixture();
        long root = builder.group("ELIGIBILITY", "ALL", "미래 배제조건");
        builder.condition(
                root,
                null,
                null,
                null,
                "conditionRole",
                "EXCLUSION",
                "inputGapCode",
                "G08",
                "referenceDateRule",
                "MOVE_IN");
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
        builder.option(unit, builder.path("검증 경로", root, null));
        var result =
                new EligibilityEvaluator()
                        .evaluate(
                                builder.build(),
                                ProfileFixtures.a(),
                                EligibilityAcceptanceTest.STANDARDS,
                                UNUSED_REGIONS);
        assertThat(result.summary()).isEqualTo(EligibilityResult.Summary.UNDETERMINED_ONLY);
        assertThat(result.options().getFirst().undeterminedKind()).isEqualTo("OPEN");
        assertThat(result.conditions().getFirst().confirmation()).isFalse();
    }

    private ConditionEvaluator evaluator(
            RuleSetFixtureBuilder builder, EligibilityProfile profile) {
        return new ConditionEvaluator(
                builder.build(), profile, EligibilityAcceptanceTest.STANDARDS, UNUSED_REGIONS);
    }
}
