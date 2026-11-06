package com.toadzip.backend.basiccomparison.domain;

import com.toadzip.backend.basiccomparison.exception.InvalidComparisonException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;

public record InputValue(ValueState state, Object value) {
    public InputValue {
        if (state == null || (state == ValueState.PROVIDED) != (value != null)) {
            throw new InvalidComparisonException("입력 값과 상태가 일치하지 않습니다.");
        }
    }

    public static InputValue unknown() {
        return new InputValue(ValueState.UNKNOWN, null);
    }

    public static InputValue provided(Object value) {
        return new InputValue(ValueState.PROVIDED, value);
    }

    public static InputValue notApplicable() {
        return new InputValue(ValueState.NOT_APPLICABLE, null);
    }

    public InputValue normalize(ProfileField field) {
        if (state != ValueState.PROVIDED) {
            return this;
        }
        try {
            return provided(normalizeValue(field));
        } catch (DateTimeParseException | ArithmeticException | NumberFormatException exception) {
            throw new InvalidComparisonException(field + ": 값의 형식이나 범위가 올바르지 않습니다.");
        }
    }

    private Object normalizeValue(ProfileField field) {
        return switch (field.type()) {
            case BOOLEAN -> typed(Boolean.class);
            case DATE -> date();
            case REGION -> region();
            case MONEY, COUNT, MONTHS -> number(field);
            case FAMILY_TYPE -> code();
            case CHILD_DATE, MARRIAGE_DATE -> event(field);
        };
    }

    private <T> T typed(Class<T> type) {
        if (!type.isInstance(value)) {
            throw new InvalidComparisonException("입력 값의 타입이 올바르지 않습니다.");
        }
        return type.cast(value);
    }

    private LocalDate date() {
        if (value instanceof LocalDate date) {
            return date;
        }
        return LocalDate.parse(typed(String.class));
    }

    private String region() {
        String code = typed(String.class);
        if (!code.matches("([0-9]{2}|[0-9]{5}|[0-9]{10})")) {
            throw new InvalidComparisonException("지역 코드 형식이 올바르지 않습니다.");
        }
        return code;
    }

    private long number(ProfileField field) {
        Number number = typed(Number.class);
        long result = new BigDecimal(number.toString()).longValueExact();
        if (result < 0 || (field == ProfileField.REGISTERED_FAMILY_COUNT && result < 1)) {
            throw new InvalidComparisonException(field + ": 음수 또는 허용되지 않는 인원수입니다.");
        }
        if (field.type() == ProfileField.Type.COUNT && result > 100) {
            throw new InvalidComparisonException("인원수가 허용 범위를 벗어났습니다.");
        }
        if (field.type() == ProfileField.Type.MONTHS && result > 1800) {
            throw new InvalidComparisonException("거주기간은 0~1800의 완료 개월 수로 입력해 주세요.");
        }
        return result;
    }

    private String code() {
        String code = typed(String.class);
        if (!Set.of("UNMARRIED", "SINGLE_PARENT", "COUPLE_OR_ENGAGED").contains(code)) {
            throw new InvalidComparisonException("알 수 없는 가족 형태입니다.");
        }
        return code;
    }

    private DateEvent event(ProfileField field) {
        DateEvent event = DateEvent.from(value);
        Set<String> allowed = Set.of("BORN", "EXPECTED");
        if (field.type() == ProfileField.Type.MARRIAGE_DATE) {
            allowed = Set.of("REGISTERED", "PLANNED");
        }
        if (!allowed.contains(event.kind())) {
            throw new InvalidComparisonException("날짜 종류가 항목과 다릅니다.");
        }
        return event;
    }
}
