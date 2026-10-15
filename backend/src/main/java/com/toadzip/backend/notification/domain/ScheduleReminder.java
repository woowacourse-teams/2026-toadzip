package com.toadzip.backend.notification.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HexFormat;

public record ScheduleReminder(long announcementId, String kind, String name, LocalDate startDate,
        LocalDate endDate, LocalTime startTime, LocalTime endTime, String scope, String condition,
        String sourceUrl, Integer sourcePage, String phase, Instant dueAt) {
    public String key() {
        String value = String.join("|", Long.toString(announcementId), kind, name, startDate.toString(),
                endDate.toString(), String.valueOf(startTime), String.valueOf(endTime), String.valueOf(scope),
                String.valueOf(condition), phase, dueAt.toString());
        try {
            return "SCHEDULE_REMINDER:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("일정 알림 키를 생성하지 못했습니다.", exception);
        }
    }
    public String message() {
        String label = switch (kind) {
            case "APPLICATION" -> "접수";
            case "DOCUMENT_SUBMISSION" -> "서류 제출";
            case "WINNER_ANNOUNCEMENT" -> "당첨자 발표";
            case "CONTRACT" -> "계약";
            default -> throw new IllegalArgumentException("지원하지 않는 알림 일정입니다.");
        };
        if (kind.equals("WINNER_ANNOUNCEMENT")) {
            return "내일 당첨자 발표가 예정되어 있어요.";
        }
        if (phase.equals("END")) {
            return "내일 " + label + " 기간이 끝나요.";
        }
        if (phase.equals("START_END")) {
            return "내일 " + label + " 일정이 있어요. 당일 마감 시간을 확인해 주세요.";
        }
        return "내일 " + label + " 일정이 시작돼요.";
    }
}
