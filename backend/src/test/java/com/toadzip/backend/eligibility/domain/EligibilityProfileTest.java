package com.toadzip.backend.eligibility.domain;

import static com.toadzip.backend.eligibility.domain.InputFact.*;
import static com.toadzip.backend.eligibility.domain.ValueState.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.eligibility.exception.InvalidEligibilityException;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

class EligibilityProfileTest {
    @Test
    void 모름과_제공된_0원은_서로_다르다() {
        EligibilityProfile profile =
                profile(Map.of(APPLICANT_MONTHLY_INCOME, new InputValue(PROVIDED, 0)));
        FactResolver facts = new FactResolver(profile, LocalDate.of(2026, 10, 15), null);

        assertThat(facts.resolve("APPLICANT_MONTHLY_INCOME").number())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(facts.resolve("HOUSEHOLD_MONTHLY_INCOME").state()).isEqualTo(UNKNOWN);
    }

    @Test
    void 자동차가_없다는_입력과_차량가액_모름은_서로_모순된다() {
        EligibilityProfile profile =
                profile(Map.of(APPLICANT_OWNS_CAR, new InputValue(PROVIDED, false)));

        assertThatThrownBy(() -> profile.validateForSave(LocalDate.of(2026, 11, 5)))
                .isInstanceOf(InvalidEligibilityException.class);
    }

    @Test
    void 해당_없는_자동차는_0원으로_평가하지만_없는_혼인일은_날짜로_만들지_않는다() {
        EligibilityProfile profile =
                profile(
                        Map.of(
                                APPLICANT_OWNS_CAR,
                                new InputValue(PROVIDED, false),
                                APPLICANT_CAR_VALUE,
                                new InputValue(NOT_APPLICABLE, null),
                                FAMILY_TYPE,
                                new InputValue(PROVIDED, "UNMARRIED"),
                                MARRIAGE_REPORT_DATE,
                                new InputValue(NOT_APPLICABLE, null)));
        profile.validateForSave(LocalDate.of(2026, 11, 5));
        FactResolver facts = new FactResolver(profile, LocalDate.of(2026, 10, 15), null);

        assertThat(facts.resolve("APPLICANT_CAR_VALUE").number()).isEqualByComparingTo("0");
        assertThat(facts.resolve("MARRIAGE_MONTHS_AT_REFERENCE").state()).isEqualTo(NOT_APPLICABLE);
    }

    @Test
    void 나이는_조회일이_아니라_공고_기준일로_계산한다() {
        EligibilityProfile profile =
                profile(Map.of(BIRTH_DATE, new InputValue(PROVIDED, "1995-10-16")));

        assertThat(
                        new FactResolver(profile, LocalDate.of(2026, 10, 15), null)
                                .resolve("AGE_AT_REFERENCE")
                                .number())
                .isEqualByComparingTo("30");
        assertThat(
                        new FactResolver(profile, LocalDate.of(2026, 10, 16), null)
                                .resolve("AGE_AT_REFERENCE")
                                .number())
                .isEqualByComparingTo("31");
    }

    @Test
    void 여러_자녀의_생년월일을_막내_생년월일로_지어내지_않는다() {
        EligibilityProfile profile =
                profile(
                        Map.of(
                                HAS_CHILDREN,
                                new InputValue(PROVIDED, true),
                                CHILDREN_COUNT,
                                new InputValue(PROVIDED, 3),
                                YOUNGEST_CHILD_BIRTH_DATE,
                                new InputValue(PROVIDED, "2024-01-01")));

        FactReading reading =
                new FactResolver(profile, LocalDate.of(2026, 10, 15), null)
                        .resolve("CHILDREN_BORN_SINCE_2023_03_28");

        assertThat(reading.number()).isEqualByComparingTo("1");
        assertThat(reading.upperBound()).isEqualByComparingTo("3");
        assertThat(reading.inputGap()).isEqualTo("G06");
    }

    @Test
    void 정수가_아닌_금액과_상태에_맞지_않는_값은_거부한다() {
        assertThatThrownBy(() -> profile(Map.of(HOUSEHOLD_ASSETS, new InputValue(PROVIDED, 1.5))))
                .isInstanceOf(InvalidEligibilityException.class);
        assertThatThrownBy(() -> profile(Map.of(HOUSEHOLD_ASSETS, new InputValue(UNKNOWN, 0))))
                .isInstanceOf(InvalidEligibilityException.class);
    }

    private EligibilityProfile profile(Map<InputFact, InputValue> facts) {
        return new EligibilityProfile(null, 0, null, facts);
    }
}
