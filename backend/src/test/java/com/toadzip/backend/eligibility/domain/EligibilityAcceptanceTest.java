package com.toadzip.backend.eligibility.domain;

import static com.toadzip.backend.eligibility.domain.EligibilityResult.Outcome.*;
import static com.toadzip.backend.eligibility.domain.EligibilityResult.Summary.*;
import static com.toadzip.backend.eligibility.domain.InputFact.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.eligibility.DongtanFixtures;
import com.toadzip.backend.eligibility.ProfileFixtures;
import com.toadzip.backend.eligibility.SacheonFixtures;
import com.toadzip.backend.eligibility.repository.CatalogRegionMembership;
import com.toadzip.backend.region.repository.CsvRegionCodeResolver;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Map;

class EligibilityAcceptanceTest {
    private static AnnotationConfigApplicationContext context;
    private static CatalogRegionMembership regions;
    public static final Map<Long, IncomeStandard> STANDARDS =
            Map.of(
                    1L,
                    new IncomeStandard(
                            1,
                            "URBAN_WORKER_MONTHLY_INCOME",
                            2025,
                            "D01 p19, D05 p5",
                            579278L,
                            8,
                            Map.of(
                                    1L, 3813363L, 2L, 5866270L, 3L, 8168429L, 4L, 8802202L, 5L,
                                    9326985L, 6L, 9906263L, 7L, 10485541L, 8L, 11064819L)));

    @BeforeAll
    static void catalog() {
        context =
                new AnnotationConfigApplicationContext(
                        CsvRegionCodeResolver.class, CatalogRegionMembership.class);
        regions = context.getBean(CatalogRegionMembership.class);
    }

    @AfterAll
    static void close() {
        context.close();
    }

    @Test
    void D01과_D05의_부분_구조화는_공개_검증을_통과한다() {
        var validator = new RuleSetValidator(regions, STANDARDS.keySet());
        validator.validateForPublication(DongtanFixtures.content());
        validator.validateForPublication(SacheonFixtures.content());
    }

    @Test
    void B의_미입력_자산만_입력_필요로_안내하고_다른_경로의_탈락은_합치지_않는다() {
        var result = evaluate(DongtanFixtures.content(), ProfileFixtures.b());
        var married = path(result, "신혼부부");
        assertThat(married.outcome()).isEqualTo(UNDETERMINED);
        assertThat(married.undeterminedKind()).isEqualTo("OPEN");
        assertThat(married.missingFactCodes()).containsExactly(HOUSEHOLD_ASSETS);
        assertThat(married.blockingConditionIds()).hasSize(2);
        assertThat(married.obligationConditionIds()).hasSize(1);
        assertThat(married.rankTierMin()).isEqualTo(1);
        assertThat(married.rankTierMax()).isEqualTo(2);
        assertThat(result.options())
                .filteredOn(option -> !option.pathLabel().equals("신혼부부"))
                .allMatch(option -> option.outcome() == NOT_ELIGIBLE);
        assertThat(result.summary()).isEqualTo(UNDETERMINED_ONLY);
        assertThat(result.needsAdditionalInput()).isTrue();
        assertThat(result.conditions())
                .anySatisfy(
                        condition -> {
                            assertThat(condition.shownValues().get("thresholdMin"))
                                    .isEqualTo(new java.math.BigDecimal("10618958"));
                            assertThat(condition.outcome()).isEqualTo(ConditionOutcome.SATISFIED);
                        });
    }

    @Test
    void B의_자산이_379000000원이면_확인사항만_남고_1원_초과하면_미충족이다() {
        var content = DongtanFixtures.content();
        var atLimit =
                evaluate(
                        content,
                        ProfileFixtures.with(ProfileFixtures.b(), HOUSEHOLD_ASSETS, 379000000));
        var overLimit =
                evaluate(
                        content,
                        ProfileFixtures.with(ProfileFixtures.b(), HOUSEHOLD_ASSETS, 379000001));
        assertThat(atLimit.summary()).isEqualTo(HAS_CONFIRMATION_ONLY);
        assertThat(atLimit.needsAdditionalInput()).isFalse();
        assertThat(path(atLimit, "신혼부부").undeterminedKind()).isEqualTo("CONFIRMATION_ONLY");
        assertThat(path(atLimit, "신혼부부").blockingConditionIds()).hasSize(1);
        assertThat(path(atLimit, "신혼부부").obligationConditionIds()).hasSize(1);
        assertThat(overLimit.summary()).isEqualTo(NONE_ELIGIBLE);
        assertThat(path(overLimit, "신혼부부").outcome()).isEqualTo(NOT_ELIGIBLE);
    }

    @Test
    void A는_본인과_세대_판정이_달라_보류하고_지역_순위는_카탈로그로_확정한다() {
        var result = evaluate(DongtanFixtures.content(), ProfileFixtures.a());
        var youth = path(result, "청년");
        assertThat(youth.outcome()).isEqualTo(UNDETERMINED);
        assertThat(youth.blockingReasons()).contains(UndeterminedReason.SUBJECT_AMBIGUOUS);
        assertThat(youth.rankTierMin()).isEqualTo(1);
        assertThat(youth.rankTierMax()).isEqualTo(1);
        assertThat(youth.rentOfferIds()).hasSize(2);
        assertThat(result.needsAdditionalInput()).isFalse();
        assertThat(result.options())
                .filteredOn(option -> option.pathLabel().equals("청년"))
                .hasSize(2);
    }

    @Test
    void C의_자목과_차목은_확인사항만_남고_순위_배점_임대_범위를_별도로_반환한다() {
        var result = evaluate(SacheonFixtures.content(), ProfileFixtures.c());
        assertThat(result.options()).hasSize(24);
        assertThat(result.summary()).isEqualTo(HAS_CONFIRMATION_ONLY);
        for (String label : java.util.List.of("자", "차")) {
            var option = path(result, label);
            assertThat(option.outcome()).isEqualTo(UNDETERMINED);
            assertThat(option.undeterminedKind()).isEqualTo("CONFIRMATION_ONLY");
            assertThat(option.blockingConditionIds()).hasSize(1);
            assertThat(option.rankTierMin()).isEqualTo(1);
            assertThat(option.rankTierMax()).isEqualTo(1);
            assertThat(option.scoreMin()).isEqualByComparingTo("71");
            assertThat(option.scoreMax()).isEqualByComparingTo("86");
            assertThat(option.rentOfferIds()).hasSize(2);
        }
        assertThat(path(result, "가").outcome()).isEqualTo(NOT_ELIGIBLE);
        assertThat(path(result, "라").outcome()).isEqualTo(NOT_ELIGIBLE);
        assertThat(path(result, "나").blockingReasons())
                .contains(UndeterminedReason.MEANING_UNSETTLED);
        assertThat(path(result, "카").blockingReasons()).contains(UndeterminedReason.AGENCY_REVIEW);
        assertThat(result.needsAdditionalInput()).isFalse();
    }

    @Test
    void 시도만_입력하면_하위_지역_조건은_일치로_단정하지_않는다() {
        assertThat(regions.match(java.util.Set.of("41590"), "41"))
                .isEqualTo(RegionMembership.Match.INSUFFICIENT_PRECISION);
        assertThat(regions.match(java.util.Set.of("41590"), "41597"))
                .isEqualTo(RegionMembership.Match.MATCH);
        assertThat(regions.match(java.util.Set.of("41110"), "41117"))
                .isEqualTo(RegionMembership.Match.MATCH);
        assertThat(regions.match(java.util.Set.of("41590"), "11"))
                .isEqualTo(RegionMembership.Match.NO_MATCH);
    }

    private EligibilityResult evaluate(RuleSetContent content, EligibilityProfile profile) {
        return new EligibilityEvaluator().evaluate(content, profile, STANDARDS, regions);
    }

    private EligibilityResult.OptionResult path(EligibilityResult result, String label) {
        return result.options().stream()
                .filter(option -> option.pathLabel().equals(label))
                .findFirst()
                .orElseThrow();
    }
}
