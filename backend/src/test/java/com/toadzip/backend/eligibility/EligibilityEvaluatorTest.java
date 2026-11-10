package com.toadzip.backend.eligibility;

import static com.toadzip.backend.eligibility.BasicComparisonFixtures.*;
import static com.toadzip.backend.eligibility.domain.EligibilityContent.*;
import static com.toadzip.backend.eligibility.domain.EligibilityDecision.Status.*;
import static com.toadzip.backend.eligibility.domain.ProfileField.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.eligibility.domain.*;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EligibilityEvaluatorTest {
    private final RegionMembership regions = new RegionMembership() {
        public Match match(Set<String> members, String supplied) {
            if (members.stream().anyMatch(supplied::startsWith)) {
                return Match.MATCH;
            }
            return Match.NO_MATCH;
        }
        public boolean isKnown(String code) { return code != null; }
    };

    @Test
    void D05_자산_미충족을_소득_미입력보다_먼저_보이고_숫자_근거를_반환한다() {
        var facts = new EnumMap<>(b().facts());
        facts.put(HOUSEHOLD_MONTHLY_INCOME, InputValue.unknown());
        facts.put(FAMILY_TOTAL_ASSETS, InputValue.provided(300000000L));
        var result = evaluate(EligibilityFixtures.d05(), new EligibilityProfile(FamilyDetailsState.PROVIDED, facts));
        assertThat(result.status()).isEqualTo(INELIGIBLE);
        assertThat(result.options().getFirst().missingInputs()).containsExactly(HOUSEHOLD_MONTHLY_INCOME);
        var first = result.paths().getFirst().checks().getFirst();
        assertThat(first.id()).isEqualTo("assets");
        assertThat(first.reading().comparedValues()).containsEntry("providedValue", 300000000L)
                .containsEntry("operator", "LTE").containsEntry("fieldLabel", FAMILY_TOTAL_ASSETS.label())
                .containsEntry("comparisonLimit", new java.math.BigDecimal("245000000"));
    }

    @Test
    void D05_소득이_비었으면_필요한_소득만_입력하도록_남긴다() {
        var facts = new EnumMap<>(b().facts());
        facts.put(HOUSEHOLD_MONTHLY_INCOME, InputValue.unknown());
        var result = evaluate(EligibilityFixtures.d05(), new EligibilityProfile(FamilyDetailsState.PROVIDED, facts));
        assertThat(result.status()).isEqualTo(UNDETERMINED);
        assertThat(result.options().getFirst().missingInputs()).containsExactly(HOUSEHOLD_MONTHLY_INCOME);
        assertThat(result.options().getFirst().requiredQualificationIds()).isEmpty();
        assertThat(result.options().getFirst().exclusionGuidanceIds()).containsExactly("history");
    }

    @Test
    void D05_소득_이하_경계와_1원_초과를_구분한다() {
        assertThat(evaluate(EligibilityFixtures.d05(), with(b(), HOUSEHOLD_MONTHLY_INCOME, 3519762L)).status())
                .isEqualTo(ELIGIBLE);
        assertThat(evaluate(EligibilityFixtures.d05(), with(b(), HOUSEHOLD_MONTHLY_INCOME, 3519763L)).status())
                .isEqualTo(INELIGIBLE);
    }

    @Test
    void D11_한부모_가족형태는_법정_자격을_자동_충족하지_않는다() {
        var result = evaluate(EligibilityFixtures.d11(), singleParent());
        assertThat(result.status()).isEqualTo(UNDETERMINED);
        assertThat(result.options()).allMatch(option -> option.status() == UNDETERMINED);
        var singleParent = result.options().stream().filter(option -> option.pathId().equals("single-parent"))
                .findFirst().orElseThrow();
        assertThat(singleParent.requiredQualificationIds()).containsExactly("single-parent");
        assertThat(singleParent.missingInputs()).isEmpty();
    }

    @Test
    void D11_서울_공통조건_미충족을_미확인_법정자격으로_보류하지_않는다() {
        var result = evaluate(EligibilityFixtures.d11(), with(singleParent(), RESIDENCE_REGION, "26110"));
        assertThat(result.status()).isEqualTo(INELIGIBLE);
        assertThat(result.options()).allMatch(option -> option.status() == INELIGIBLE);
    }

    @Test
    void D01_수지구_선택지가_미충족이어도_기흥구_지원가능은_유지한다() {
        var result = evaluate(EligibilityFixtures.d01(), youth());
        assertThat(result.status()).isEqualTo(ELIGIBLE);
        assertThat(result.eligibleOptionIds()).containsExactly("giheung-youth");
        assertThat(result.eligibleOptionIdsIn("41465", regions)).isEmpty();
        assertThat(result.eligibleOptionIdsIn("41463", regions)).containsExactly("giheung-youth");
        assertThat(result.eligibleOptionIdsIn("41", regions)).containsExactly("giheung-youth");
    }

    @Test
    void 미모집_경로는_지원가능_근거가_아니며_일부취소를_다른_선택지에_확대하지_않는다() {
        var source = EligibilityFixtures.d01();
        var cancelledSuji = options(source, List.of(
                new ApplicationOption("suji-newlywed", "suji", "newlywed", RecruitmentStatus.CANCELLED),
                source.options().get(1)));
        var result = evaluate(cancelledSuji, youth());
        assertThat(result.status()).isEqualTo(ELIGIBLE);
        assertThat(result.options().getFirst().status()).isEqualTo(NOT_EVALUATED);
        var noYouth = options(source, List.of(source.options().getFirst(),
                new ApplicationOption("giheung-youth", "giheung", "youth", RecruitmentStatus.NOT_RECRUITING)));
        assertThat(evaluate(noYouth, youth()).status()).isEqualTo(INELIGIBLE);
    }

    @Test
    void 비어있거나_모집여부_미확인인_경로를_모두_미충족이라고_간주하지_않는다() {
        var source = EligibilityFixtures.d01();
        assertThat(evaluate(options(source, List.of()), youth()).status()).isEqualTo(NOT_EVALUATED);
        var unknown = options(source, List.of(source.options().getFirst(),
                new ApplicationOption("giheung-youth", "giheung", "youth", RecruitmentStatus.UNKNOWN)));
        assertThat(evaluate(unknown, youth()).status()).isEqualTo(UNDETERMINED);
        assertThat(evaluate(sacheon(), b()).status()).isEqualTo(NOT_EVALUATED);
    }

    @Test
    void 모집예정_실제_선택지도_판정한다() {
        var source = EligibilityFixtures.d01();
        var upcoming = options(source, List.of(new ApplicationOption(
                "giheung-youth", "giheung", "youth", RecruitmentStatus.UPCOMING)));
        assertThat(evaluate(upcoming, youth()).status()).isEqualTo(ELIGIBLE);
    }

    @Test
    void 공통_거주_입력은_값과_비교하고_다른_미입력은_요구하지_않는다() {
        var rule = new ProfileRule(ProfileRule.Operator.NUMBER_RANGE, CURRENT_REGION_RESIDENCE_MONTHS,
                null, 24L, 1800L, List.of(), null, List.of());
        var reference = LocalDate.of(2026, 10, 16);
        var profile = with(EligibilityProfile.empty(), CURRENT_REGION_RESIDENCE_MONTHS, 24L);
        var evaluator = new ProfileRuleEvaluator(reference, profile, STANDARDS, regions);
        assertThat(evaluator.evaluate(rule).status()).isEqualTo(RuleReading.Status.SATISFIED);
        assertThat(evaluator.evaluate(rule).missingInputs()).isEmpty();
        assertThat(new ProfileRuleEvaluator(reference,
                with(profile, CURRENT_REGION_RESIDENCE_MONTHS, 23L), STANDARDS, regions).evaluate(rule).status())
                .isEqualTo(RuleReading.Status.NOT_SATISFIED);
        var lease = new ProfileRule(ProfileRule.Operator.DAYS_FROM_REFERENCE, CURRENT_LEASE_END_DATE,
                null, 0L, 90L, List.of(), null, List.of());
        assertThat(new ProfileRuleEvaluator(reference, with(profile, CURRENT_LEASE_END_DATE, reference.plusDays(90)),
                STANDARDS, regions).evaluate(lease).status()).isEqualTo(RuleReading.Status.SATISFIED);
        assertThat(new ProfileRuleEvaluator(reference, with(profile, CURRENT_LEASE_END_DATE, reference.plusDays(91)),
                STANDARDS, regions).evaluate(lease).status()).isEqualTo(RuleReading.Status.NOT_SATISFIED);
        var facts = new EnumMap<>(profile.facts());
        facts.put(CURRENT_LEASE_END_DATE, InputValue.notApplicable());
        var absent = new ProfileRuleEvaluator(reference,
                new EligibilityProfile(FamilyDetailsState.NOT_STARTED, facts),
                STANDARDS, regions).evaluate(lease);
        assertThat(absent.status()).isEqualTo(RuleReading.Status.NOT_SATISFIED);
        assertThat(absent.missingInputs()).isEmpty();
    }

    @Test
    void 자녀없음의_0명과_해당없음은_같은_조건_결과를_낸다() {
        var facts = new EnumMap<>(b().facts());
        facts.put(CHILDREN_COUNT_INCLUDING_FETUS, InputValue.notApplicable());
        var profile = new EligibilityProfile(FamilyDetailsState.PROVIDED, facts);
        profile.validate(LocalDate.of(2026, 10, 16));
        var evaluator = new ProfileRuleEvaluator(LocalDate.of(2026, 10, 16), profile, STANDARDS, regions);
        assertThat(evaluator.evaluate(equal(CHILDREN_COUNT_INCLUDING_FETUS, "0")).status())
                .isEqualTo(RuleReading.Status.SATISFIED);
        var range = new ProfileRule(ProfileRule.Operator.NUMBER_RANGE, CHILDREN_COUNT_INCLUDING_FETUS,
                null, 0L, 0L, List.of(), null, List.of());
        assertThat(evaluator.evaluate(range).status()).isEqualTo(RuleReading.Status.SATISFIED);
    }

    @Test
    void 공통_미충족은_모집여부_미확인으로_사라지지_않고_취소와도_구분한다() {
        var content = EligibilityFixtures.d11();
        var unknown = options(content, content.options().stream().map(option -> new ApplicationOption(
                option.id(), option.supplyUnitId(), option.pathId(), RecruitmentStatus.UNKNOWN)).toList());
        assertThat(evaluate(unknown, b()).status()).isEqualTo(INELIGIBLE);
        var cancelled = options(content, content.options().stream().map(option -> new ApplicationOption(
                option.id(), option.supplyUnitId(), option.pathId(), RecruitmentStatus.CANCELLED)).toList());
        assertThat(evaluate(cancelled, b()).status()).isEqualTo(NOT_EVALUATED);
    }

    private EligibilityProfile youth() {
        return new EligibilityProfile(FamilyDetailsState.NOT_STARTED, Map.of(
                BIRTH_DATE, InputValue.provided("1997-01-10"), FAMILY_TYPE, InputValue.provided("UNMARRIED")));
    }

    private EligibilityDecision evaluate(EligibilityContent content, EligibilityProfile profile) {
        return new EligibilityEvaluator().evaluate(content, profile, STANDARDS, regions);
    }

    private EligibilityContent options(EligibilityContent content, List<ApplicationOption> options) {
        return new EligibilityContent(content.metadata(), content.documents(), content.evidences(), content.supplyUnits(),
                content.paths(), options, content.guidances(), content.quantities(), content.rentOffers(),
                content.schedules(), content.commonChecks(), content.qualifications());
    }
}
