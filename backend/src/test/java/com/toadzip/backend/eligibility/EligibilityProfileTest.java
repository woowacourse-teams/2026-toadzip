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
    void 혼인예정일이_지나도_실제_혼인기간으로_계산하지_않는다() {
        var user = with(b(), MARRIAGE_EVENT, new DateEvent("PLANNED", LocalDate.of(2020, 1, 1)));
        user.validate(TODAY);
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
        var reading = new ProfileRuleEvaluator(TODAY, user, STANDARDS, null).evaluate(rule);
        assertThat(reading.status()).isEqualTo(RuleReading.Status.DIRECT_CHECK_REQUIRED);
        assertThat(user.value(MARRIAGE_EVENT).value())
                .isEqualTo(new DateEvent("PLANNED", LocalDate.of(2020, 1, 1)));
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
    void 출산예정일과_실제_생년월일을_구분하고_태아를_태어난_아이로_추정하지_않는다() {
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
                .isEqualTo(RuleReading.Status.DIRECT_CHECK_REQUIRED);
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
