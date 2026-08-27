package com.toadzip.backend.announcement.domain;

import com.toadzip.backend.global.persistence.LegacyStoredValue;

public enum ReceptionMethod implements LegacyStoredValue {
    ONLINE("인터넷"),
    VISIT("현장"),
    MAIL("우편"),
    ETC("기타");

    private final String legacyStoredValue;

    ReceptionMethod(String legacyStoredValue) {
        this.legacyStoredValue = legacyStoredValue;
    }

    public static ReceptionMethod fromStoredValue(String value) {
        return switch (value) {
            case "ONLINE", "인터넷" -> ONLINE;
            case "VISIT", "현장" -> VISIT;
            case "MAIL", "우편" -> MAIL;
            case "ETC", "기타" -> ETC;
            default -> throw new IllegalArgumentException("알 수 없는 접수방식 코드다.");
        };
    }

    @Override
    public String legacyStoredValue() {
        return legacyStoredValue;
    }
}
