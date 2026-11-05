package com.toadzip.backend.eligibility.domain;

import static com.toadzip.backend.eligibility.domain.InputFact.*;

import com.toadzip.backend.eligibility.exception.InvalidEligibilityException;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record EligibilityProfile(
        Long id, long version, Instant updatedAt, Map<InputFact, InputValue> facts) {
    public EligibilityProfile {
        Map<InputFact, InputValue> normalized = new EnumMap<>(InputFact.class);
        for (InputFact fact : InputFact.values()) {
            normalized.put(fact, InputValue.unknown());
        }
        if (facts != null) {
            facts.forEach(
                    (fact, value) -> {
                        if (fact == null || value == null) {
                            throw new InvalidEligibilityException("입력 항목과 상태는 null일 수 없습니다.");
                        }
                        normalized.put(fact, value.normalize(fact));
                    });
        }
        facts = Map.copyOf(normalized);
    }

    public static EligibilityProfile unknown() {
        return new EligibilityProfile(null, 0, null, Map.of());
    }

    public InputValue value(InputFact fact) {
        return facts.get(fact);
    }

    public void validateForSave(java.time.LocalDate today) {
        verifyDependent(APPLICANT_OWNS_CAR, APPLICANT_CAR_VALUE);
        verifyDependent(HOUSEHOLD_HAS_CAR_OWNER, HOUSEHOLD_CAR_VALUE);
        verifyDependent(HAS_CHILDREN, CHILDREN_COUNT);
        verifyDependent(HAS_CHILDREN, YOUNGEST_CHILD_BIRTH_DATE);
        if (provided(IS_UNIVERSITY_STUDENT, false)) {
            requireNotApplicable(UNIVERSITY_REGION);
        }
        if (provided(FAMILY_TYPE, "UNMARRIED")) {
            requireNotApplicable(MARRIAGE_REPORT_DATE);
        }
        boolean absent = value(SAVINGS_PAYMENT_COUNT).state() == ValueState.NOT_APPLICABLE;
        for (InputFact fact : java.util.List.of(SAVINGS_PAID_AMOUNT, SAVINGS_PERIOD)) {
            if (absent != (value(fact).state() == ValueState.NOT_APPLICABLE)) {
                throw new InvalidEligibilityException("청약통장 세 항목의 해당 없음 상태가 일치해야 합니다.");
            }
        }
        for (InputFact fact : java.util.List.of(BIRTH_DATE, YOUNGEST_CHILD_BIRTH_DATE)) {
            if (value(fact).value() instanceof java.time.LocalDate date && date.isAfter(today)) {
                throw new InvalidEligibilityException(fact + ": 생년월일은 미래일 수 없습니다.");
            }
        }
    }

    private void verifyDependent(InputFact indicator, InputFact dependent) {
        if (provided(indicator, false)) {
            requireNotApplicable(dependent);
        }
        if (provided(indicator, true) && value(dependent).state() == ValueState.NOT_APPLICABLE) {
            throw new InvalidEligibilityException(dependent + ": 해당 대상이 있으므로 해당 없음일 수 없습니다.");
        }
    }

    private void requireNotApplicable(InputFact fact) {
        if (value(fact).state() != ValueState.NOT_APPLICABLE) {
            throw new InvalidEligibilityException(fact + ": 해당 없음으로 입력해야 합니다.");
        }
    }

    private boolean provided(InputFact fact, Object expected) {
        return value(fact).state() == ValueState.PROVIDED
                && Objects.equals(value(fact).value(), expected);
    }
}
