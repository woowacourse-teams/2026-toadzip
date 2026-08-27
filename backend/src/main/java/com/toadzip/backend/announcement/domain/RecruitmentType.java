package com.toadzip.backend.announcement.domain;

import com.toadzip.backend.global.persistence.LegacyStoredValue;

public enum RecruitmentType implements LegacyStoredValue {
    NEW("신규모집"),
    WAITLIST("예비입주자"),
    ETC("기타");

    private final String legacyStoredValue;

    RecruitmentType(String legacyStoredValue) {
        this.legacyStoredValue = legacyStoredValue;
    }

    public static RecruitmentType fromStoredValue(String value) {
        return switch (value) {
            case "NEW", "신규모집" -> NEW;
            case "WAITLIST", "예비입주자" -> WAITLIST;
            case "ETC", "기타" -> ETC;
            default -> throw new IllegalArgumentException("알 수 없는 모집유형 코드다.");
        };
    }

    @Override
    public String legacyStoredValue() {
        return legacyStoredValue;
    }
}
