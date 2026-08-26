package com.toadzip.backend.announcement.domain;

import com.toadzip.backend.global.persistence.LegacyStoredValue;

public enum AttachmentType implements LegacyStoredValue {
    ANNOUNCEMENT("공고문"),
    CORRECTION("정정공고"),
    CANCELLATION("취소공고"),
    REFERENCE("참고자료"),
    ETC("기타");

    private final String legacyStoredValue;

    AttachmentType(String legacyStoredValue) {
        this.legacyStoredValue = legacyStoredValue;
    }

    public static AttachmentType fromStoredValue(String value) {
        return switch (value) {
            case "ANNOUNCEMENT", "공고문" -> ANNOUNCEMENT;
            case "CORRECTION", "정정공고" -> CORRECTION;
            case "CANCELLATION", "취소공고" -> CANCELLATION;
            case "REFERENCE", "참고자료" -> REFERENCE;
            case "ETC", "기타" -> ETC;
            default -> throw new IllegalArgumentException("알 수 없는 첨부파일 유형 코드다.");
        };
    }

    @Override
    public String legacyStoredValue() {
        return legacyStoredValue;
    }
}
