package com.toadzip.backend.announcement.domain;

public enum RecruitmentType {
    NEW,
    WAITLIST,
    ETC;

    public static RecruitmentType fromStoredValue(String value) {
        return switch (value) {
            case "NEW", "신규모집" -> NEW;
            case "WAITLIST", "예비입주자" -> WAITLIST;
            case "ETC", "기타" -> ETC;
            default -> throw new IllegalArgumentException("알 수 없는 모집유형 코드다.");
        };
    }
}
