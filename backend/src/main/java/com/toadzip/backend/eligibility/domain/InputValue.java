package com.toadzip.backend.eligibility.domain;

import com.toadzip.backend.eligibility.exception.InvalidEligibilityException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public record InputValue(ValueState state, Object value) {
    public static InputValue unknown() {
        return new InputValue(ValueState.UNKNOWN, null);
    }

    public InputValue normalize(InputFact fact) {
        if (state == null || (state == ValueState.PROVIDED) != (value != null)) {
            throw new InvalidEligibilityException(fact + ": 값과 상태가 일치하지 않습니다.");
        }
        if (state == ValueState.NOT_APPLICABLE && !fact.allowsNotApplicable()) {
            throw new InvalidEligibilityException(fact + ": 해당 없음은 허용되지 않습니다.");
        }
        if (state != ValueState.PROVIDED) {
            return this;
        }
        try {
            return new InputValue(state, normalizeProvided(fact));
        } catch (ArithmeticException | DateTimeParseException exception) {
            throw new InvalidEligibilityException(fact + ": 값의 형식 또는 범위가 올바르지 않습니다.");
        }
    }

    private Object normalizeProvided(InputFact fact) {
        return switch (fact.valueType()) {
            case BOOLEAN -> requireType(fact, Boolean.class);
            case DATE -> date(fact);
            case INTEGER, KRW, MONTHS -> integer(fact);
            case CODE -> familyType(fact);
            case REGION -> region(fact);
        };
    }

    private Object requireType(InputFact fact, Class<?> type) {
        if (!type.isInstance(value)) {
            throw new InvalidEligibilityException(fact + ": 값의 종류가 올바르지 않습니다.");
        }
        return value;
    }

    private LocalDate date(InputFact fact) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        return LocalDate.parse((String) requireType(fact, String.class));
    }

    private long integer(InputFact fact) {
        requireType(fact, Number.class);
        long result = new BigDecimal(value.toString()).longValueExact();
        if (result < 0
                || ((fact == InputFact.FAMILY_MEMBER_COUNT || fact == InputFact.CHILDREN_COUNT)
                        && result < 1)
                || (fact == InputFact.PARENTS_COUNT && result > 2)) {
            throw new InvalidEligibilityException(fact + ": 허용 범위를 벗어났습니다.");
        }
        return result;
    }

    private String familyType(InputFact fact) {
        String text = (String) requireType(fact, String.class);
        if (!java.util.Set.of("COUPLE_OR_ENGAGED", "SINGLE_PARENT", "UNMARRIED").contains(text)) {
            throw new InvalidEligibilityException(fact + ": 알 수 없는 가족형태입니다.");
        }
        return text;
    }

    private String region(InputFact fact) {
        String text = (String) requireType(fact, String.class);
        if (!text.matches("([0-9]{2}|[0-9]{5}|[0-9]{10})")) {
            throw new InvalidEligibilityException(fact + ": 지역 코드 형식이 올바르지 않습니다.");
        }
        return text;
    }
}
