package com.toadzip.backend.eligibility.domain;

import com.toadzip.backend.eligibility.exception.InvalidEligibilityException;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

public record DateEvent(String kind, LocalDate date) {
    public DateEvent {
        if (kind == null || date == null) {
            throw new InvalidEligibilityException("날짜 종류와 날짜가 필요합니다.");
        }
    }

    public static DateEvent from(Object value) {
        if (value instanceof DateEvent event) {
            return event;
        }
        if (value instanceof Map<?, ?> fields && fields.keySet().equals(Set.of("kind", "date"))) {
            if (fields.get("kind") instanceof String kind
                    && fields.get("date") instanceof String date) {
                return new DateEvent(kind, LocalDate.parse(date));
            }
        }
        throw new InvalidEligibilityException("날짜 사건의 형식이 올바르지 않습니다.");
    }

    public boolean actual() {
        return Set.of("REGISTERED", "BORN").contains(kind);
    }
}
