package com.toadzip.backend.announcement.domain;

import com.toadzip.backend.global.persistence.LegacyStoredValue;

public enum AnnouncementPublicationType implements LegacyStoredValue {
    ORIGINAL("원공고"),
    CORRECTION("정정공고"),
    CANCELLATION("취소공고");

    private final String legacyStoredValue;

    AnnouncementPublicationType(String legacyStoredValue) {
        this.legacyStoredValue = legacyStoredValue;
    }

    public static AnnouncementPublicationType fromStoredValue(String value) {
        return switch (value) {
            case "ORIGINAL", "원공고" -> ORIGINAL;
            case "CORRECTION", "정정공고" -> CORRECTION;
            case "CANCELLATION", "취소공고" -> CANCELLATION;
            default -> throw new IllegalArgumentException("알 수 없는 공고 유형 코드다.");
        };
    }

    @Override
    public String legacyStoredValue() {
        return legacyStoredValue;
    }
}
