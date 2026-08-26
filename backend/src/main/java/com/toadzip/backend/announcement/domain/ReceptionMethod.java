package com.toadzip.backend.announcement.domain;

public enum ReceptionMethod {
    ONLINE,
    VISIT,
    MAIL,
    ETC;

    public static ReceptionMethod fromStoredValue(String value) {
        return switch (value) {
            case "ONLINE", "인터넷" -> ONLINE;
            case "VISIT", "현장" -> VISIT;
            case "MAIL", "우편" -> MAIL;
            case "ETC", "기타" -> ETC;
            default -> throw new IllegalArgumentException("알 수 없는 접수방식 코드다.");
        };
    }
}
