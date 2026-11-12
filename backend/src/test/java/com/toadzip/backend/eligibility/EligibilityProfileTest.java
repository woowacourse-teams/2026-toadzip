package com.toadzip.backend.eligibility;

import static com.toadzip.backend.eligibility.BasicComparisonFixtures.*;
import static com.toadzip.backend.eligibility.domain.ProfileField.*;

import static org.assertj.core.api.Assertions.*;

import com.toadzip.backend.eligibility.domain.*;
import com.toadzip.backend.eligibility.exception.InvalidEligibilityException;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

class EligibilityProfileTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    @Test
    void 현재_입력과_선택_생략을_검증한다() {
        assertThatCode(() -> b().validate(TODAY)).doesNotThrowAnyException();
        assertThatCode(() -> skipped().validate(TODAY)).doesNotThrowAnyException();
        assertThat(skipped().value(HOUSEHOLD_MONTHLY_INCOME).value()).isEqualTo(3000000L);
        assertThat(skipped().value(FAMILY_TOTAL_ASSETS).value()).isNull();
        assertThatThrownBy(
                        () ->
                                new EligibilityProfile(
                                        FamilyDetailsState.SKIPPED,
                                        Map.of(FAMILY_TOTAL_ASSETS, InputValue.provided(0))))
                .isInstanceOf(InvalidEligibilityException.class);
    }

    @Test
    void 가족수와_세대분리를_세대주나_다른_신분으로_변환하지_않는다() {
        var user =
                new EligibilityProfile(
                        FamilyDetailsState.NOT_STARTED,
                        Map.of(
                                FAMILY_TYPE,
                                InputValue.provided("UNMARRIED"),
                                REGISTERED_FAMILY_COUNT,
                                InputValue.provided(1),
                                IS_HOUSEHOLD_SEPARATED,
                                InputValue.provided(true)));
        user.validate(TODAY);
        assertThat(user.facts()).hasSize(ProfileField.values().length);
        for (String omitted :
                List.of(
                        "IS_HOUSEHOLD_HEAD",
                        "SUPPORTED_SINGLE_PARENT_FAMILY",
                        "SAVINGS_PAYMENT_COUNT",
                        "LEASE_END_DATE",
                        "UNIVERSITY_REGION")) {
            assertThatThrownBy(() -> ProfileField.valueOf(omitted))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 부모_소득은_대학생_독립_무소득_예외에서만_질문한다() {
        var facts = new EnumMap<ProfileField, InputValue>(ProfileField.class);
        facts.put(FAMILY_TYPE, InputValue.provided("UNMARRIED"));
        facts.put(IS_UNIVERSITY_STUDENT, InputValue.provided(true));
        facts.put(IS_HOUSEHOLD_SEPARATED, InputValue.provided(true));
        facts.put(APPLICANT_MONTHLY_INCOME, InputValue.provided(0));
        var user = new EligibilityProfile(FamilyDetailsState.NOT_STARTED, facts);
        assertThat(user.access(PARENTS_MONTHLY_INCOME).state())
                .isEqualTo(EligibilityProfile.AccessState.ACTIVE);
        var paid = with(user, APPLICANT_MONTHLY_INCOME, 1L);
        assertThat(paid.access(PARENTS_MONTHLY_INCOME).state())
                .isEqualTo(EligibilityProfile.AccessState.NOT_COLLECTED);
        assertThatThrownBy(() -> with(paid, PARENTS_MONTHLY_INCOME, 1000000L).validate(TODAY))
                .isInstanceOf(InvalidEligibilityException.class);
        facts.put(APPLICANT_MONTHLY_INCOME, InputValue.unknown());
        assertThat(
                        new EligibilityProfile(FamilyDetailsState.NOT_STARTED, facts)
                                .access(PARENTS_MONTHLY_INCOME)
                                .dependencies())
                .contains(APPLICANT_MONTHLY_INCOME);
    }

    @Test
    void 혼인_예정이면_예정일과_상관없이_기준일에_막_혼인한_것으로_보고_혼인기간을_비교한다() {
        // 예정일이 지난 예정 입력도 실제 혼인기간으로 계산하지 않는다(2020년 예정이 '6년 전 혼인'이 되지 않게).
        var user = with(b(), MARRIAGE_EVENT, new DateEvent("PLANNED", LocalDate.of(2020, 1, 1)));
        user.validate(TODAY);
        var evaluator = new ProfileRuleEvaluator(TODAY, user, STANDARDS, null);
        var reading = evaluator.evaluate(marriedWithin(0L, 84L));
        assertThat(reading.status()).isEqualTo(RuleReading.Status.SATISFIED);
        assertThat(reading.comparedValues()).containsEntry("comparedValue", 0L)
                .containsEntry("providedValue", new DateEvent("PLANNED", LocalDate.of(2020, 1, 1)));
        assertThat(evaluator.evaluate(marriedWithin(12L, 84L)).status())
                .isEqualTo(RuleReading.Status.NOT_SATISFIED);
        assertThat(user.value(MARRIAGE_EVENT).value())
                .isEqualTo(new DateEvent("PLANNED", LocalDate.of(2020, 1, 1)));
    }

    private static ProfileRule marriedWithin(long minimum, long maximum) {
        return new ProfileRule(ProfileRule.Operator.MONTHS_SINCE, MARRIAGE_EVENT, null, minimum, maximum,
                List.of(), null, List.of());
    }

    @Test
    void 혼인기간_경계는_개월_버림으로_하루를_더_허용하지_않는다() {
        ProfileRule rule =
                new ProfileRule(
                        ProfileRule.Operator.MONTHS_SINCE,
                        MARRIAGE_EVENT,
                        null,
                        0L,
                        84L,
                        List.of(),
                        null,
                        List.of());
        var atLimit = with(b(), MARRIAGE_EVENT, new DateEvent("REGISTERED", TODAY.minusYears(7)));
        var over =
                with(
                        b(),
                        MARRIAGE_EVENT,
                        new DateEvent("REGISTERED", TODAY.minusYears(7).minusDays(1)));
        assertThat(new ProfileRuleEvaluator(TODAY, atLimit, STANDARDS, null).evaluate(rule).status())
                .isEqualTo(RuleReading.Status.SATISFIED);
        assertThat(new ProfileRuleEvaluator(TODAY, over, STANDARDS, null).evaluate(rule).status())
                .isEqualTo(RuleReading.Status.NOT_SATISFIED);
    }

    @Test
    void 출산_예정이면_기준일에_막_태어난_0세_아이로_보고_실제_생년월일의_미래_날짜는_받지_않는다() {
        var user =
                with(
                        with(
                                with(b(), HAS_CHILDREN_OR_PREGNANCY, true),
                                CHILDREN_COUNT_INCLUDING_FETUS,
                                1L),
                        YOUNGEST_CHILD,
                        new DateEvent("EXPECTED", TODAY.plusMonths(3)));
        assertThatCode(() -> user.validate(TODAY)).doesNotThrowAnyException();
        assertThat(
                        new ProfileRuleEvaluator(TODAY, user, STANDARDS, null)
                                .evaluate(age(YOUNGEST_CHILD, 0, 6))
                                .status())
                .isEqualTo(RuleReading.Status.SATISFIED);
        assertThat(
                        new ProfileRuleEvaluator(TODAY, user, STANDARDS, null)
                                .evaluate(age(YOUNGEST_CHILD, 1, 6))
                                .status())
                .isEqualTo(RuleReading.Status.NOT_SATISFIED);
        assertThatThrownBy(
                        () ->
                                with(user, YOUNGEST_CHILD, new DateEvent("BORN", TODAY.plusDays(1)))
                                        .validate(TODAY))
                .isInstanceOf(InvalidEligibilityException.class);
    }

    @Test
    void 현재_입력_미입력과_현재_흐름에서_수집하지_않는_값을_구분한다() {
        var rule = amount(PARENTS_MONTHLY_INCOME, 3000000L);
        assertThat(new ProfileRuleEvaluator(TODAY, b(), STANDARDS, null).evaluate(rule).status())
                .isEqualTo(RuleReading.Status.DIRECT_CHECK_REQUIRED);
        var missing =
                new ProfileRuleEvaluator(TODAY, EligibilityProfile.empty(), STANDARDS, null)
                        .evaluate(amount(APPLICANT_ASSETS, 100L));
        assertThat(missing.status()).isEqualTo(RuleReading.Status.INPUT_NEEDED);
        assertThat(missing.missingInputs()).containsExactly(APPLICANT_ASSETS);
    }

    @Test
    void 자동차_없음은_알지만_자산_해당없음을_0원으로_취급하지_않는다() {
        var user = b();
        assertThat(
                        new ProfileRuleEvaluator(TODAY, user, STANDARDS, null)
                                .evaluate(amount(FAMILY_CAR_VALUE, 10L))
                                .status())
                .isEqualTo(RuleReading.Status.SATISFIED);
        var facts = new EnumMap<>(user.facts());
        facts.put(FAMILY_TOTAL_ASSETS, InputValue.notApplicable());
        assertThatThrownBy(
                        () ->
                                new EligibilityProfile(FamilyDetailsState.PROVIDED, facts)
                                        .validate(TODAY))
                .isInstanceOf(InvalidEligibilityException.class);
    }
}
