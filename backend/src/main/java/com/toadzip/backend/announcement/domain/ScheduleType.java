package com.toadzip.backend.announcement.domain;

public enum ScheduleType {
    APPLICATION,
    DOCUMENT_SUBMISSION,
    WINNER_ANNOUNCEMENT,
    CONTRACT,
    MOVE_IN,
    ETC;

    public static ScheduleType fromStoredValue(String value) {
        return switch (value) {
            case "APPLICATION", "접수" -> APPLICATION;
            case "DOCUMENT_SUBMISSION", "서류제출" -> DOCUMENT_SUBMISSION;
            case "WINNER_ANNOUNCEMENT", "당첨자발표" -> WINNER_ANNOUNCEMENT;
            case "CONTRACT", "계약" -> CONTRACT;
            case "MOVE_IN", "입주" -> MOVE_IN;
            case "ETC", "기타" -> ETC;
            default -> throw new IllegalArgumentException("알 수 없는 일정유형 코드다.");
        };
    }
}
