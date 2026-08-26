package com.toadzip.backend.announcement.domain;

public enum AnnouncementPublicationType {
    ORIGINAL,
    CORRECTION,
    CANCELLATION;

    public static AnnouncementPublicationType fromStoredValue(String value) {
        return switch (value) {
            case "ORIGINAL", "원공고" -> ORIGINAL;
            case "CORRECTION", "정정공고" -> CORRECTION;
            case "CANCELLATION", "취소공고" -> CANCELLATION;
            default -> throw new IllegalArgumentException("알 수 없는 공고 유형 코드다.");
        };
    }
}
