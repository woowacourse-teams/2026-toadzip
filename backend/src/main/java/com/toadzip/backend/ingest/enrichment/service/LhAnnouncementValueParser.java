package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class LhAnnouncementValueParser {

    private static final Pattern DATE_TIME = Pattern.compile(
            "(20\\d{2})\\D*(\\d{1,2})\\D*(\\d{1,2})(?:\\D+(\\d{1,2})\\D*(\\d{2}))?"
    );
    private static final Pattern YEAR_MONTH = Pattern.compile("((?:19|20)\\d{2})\\D*(\\d{1,2})");
    private static final Pattern UNSIGNED_INTEGER = Pattern.compile("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)");

    List<LocalDateTime> dateTimes(String value, String fieldName) {
        List<LocalDateTime> values = new ArrayList<>();
        Matcher matcher = DATE_TIME.matcher(value);
        while (matcher.find()) {
            values.add(dateTime(matcher, fieldName));
        }
        return values;
    }

    LocalDateTime dateTime(String value, String fieldName) {
        Matcher matcher = DATE_TIME.matcher(value);
        if (!matcher.find()) {
            throw invalid(fieldName + " 형식이 올바르지 않습니다.");
        }
        return dateTime(matcher, fieldName);
    }

    private LocalDateTime dateTime(Matcher matcher, String fieldName) {
        try {
            int hour = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
            int minute = matcher.group(5) == null ? 0 : Integer.parseInt(matcher.group(5));
            return LocalDateTime.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    hour,
                    minute
            );
        }
        catch (DateTimeException | NumberFormatException exception) {
            throw invalid(fieldName + " 형식이 올바르지 않습니다.");
        }
    }

    YearMonth yearMonth(String value, String fieldName) {
        if (unavailable(value)) {
            return null;
        }
        Matcher matcher = YEAR_MONTH.matcher(value);
        if (!matcher.find()) {
            throw invalid(fieldName + " 형식이 올바르지 않습니다.");
        }
        try {
            return YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        }
        catch (DateTimeException | NumberFormatException exception) {
            throw invalid(fieldName + " 형식이 올바르지 않습니다.");
        }
    }

    Integer integer(String value, String fieldName) {
        if (numericUnavailable(value)) {
            return null;
        }
        String digits = integerDigits(value, fieldName);
        try {
            return Integer.valueOf(digits);
        }
        catch (NumberFormatException exception) {
            throw invalid(fieldName + " 형식이 올바르지 않습니다.");
        }
    }

    BigDecimal amount(String value, String fieldName) {
        if (numericUnavailable(value)) {
            return null;
        }
        BigDecimal amount = new BigDecimal(integerDigits(value, fieldName));
        try {
            amount.longValueExact();
            return amount;
        }
        catch (ArithmeticException exception) {
            throw invalid(fieldName + " 형식이 올바르지 않습니다.");
        }
    }

    private String integerDigits(String value, String fieldName) {
        String normalized = value.strip();
        if (!UNSIGNED_INTEGER.matcher(normalized).matches()) {
            throw invalid(fieldName + " 형식이 올바르지 않습니다.");
        }
        return normalized.replace(",", "");
    }

    boolean blank(String value) {
        return value == null || value.isBlank();
    }

    boolean unavailable(String value) {
        if (blank(value)) {
            return true;
        }
        String normalized = value.replaceAll("\\s+", "").strip();
        return normalized.startsWith("2999")
                || normalized.startsWith("9999")
                || unavailableMarker(normalized);
    }

    private boolean numericUnavailable(String value) {
        if (blank(value)) {
            return true;
        }
        String normalized = value.replaceAll("\\s+", "").strip();
        return unavailableMarker(normalized);
    }

    private boolean unavailableMarker(String normalized) {
        return normalized.equals("~")
                || normalized.equals("-")
                || normalized.contains("공고문참조")
                || normalized.contains("미정")
                || normalized.contains("별도 안내");
    }

    LhAnnouncementEnrichmentRejectedException invalid(String detail) {
        return new LhAnnouncementEnrichmentRejectedException(
                LhAnnouncementEnrichmentFailureReason.INVALID_VALUE,
                detail
        );
    }
}
