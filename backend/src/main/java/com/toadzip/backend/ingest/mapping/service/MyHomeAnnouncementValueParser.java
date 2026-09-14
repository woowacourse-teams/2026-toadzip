package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

class MyHomeAnnouncementValueParser {

    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DOTTED_DATE = DateTimeFormatter.ofPattern("uuuu.MM.dd")
            .withResolverStyle(ResolverStyle.STRICT);

    String requiredText(
            List<MyHomeAnnouncementSource> sources,
            Function<MyHomeAnnouncementSource, String> extractor,
            String fieldName
    ) {
        String value = optionalText(sources, extractor, fieldName);
        if (value == null) {
            throw missing(fieldName);
        }
        return value;
    }

    String optionalText(
            List<MyHomeAnnouncementSource> sources,
            Function<MyHomeAnnouncementSource, String> extractor,
            String fieldName
    ) {
        Set<String> values = new LinkedHashSet<>();
        for (MyHomeAnnouncementSource source : sources) {
            String value = normalizedText(extractor.apply(source));
            if (value != null) {
                values.add(value);
            }
        }
        if (values.size() > 1) {
            throw conflict(fieldName);
        }
        return values.stream().findFirst().orElse(null);
    }

    String requiredText(String value, String fieldName) {
        String normalized = normalizedText(value);
        if (normalized == null) {
            throw missing(fieldName);
        }
        return normalized;
    }

    LocalDate commonDate(
            List<MyHomeAnnouncementSource> sources,
            Function<MyHomeAnnouncementSource, String> extractor,
            String fieldName
    ) {
        return date(requiredText(sources, extractor, fieldName), fieldName);
    }

    private LocalDate date(String value, String fieldName) {
        DateTimeFormatter formatter = COMPACT_DATE;
        if (value.contains(".")) {
            formatter = DOTTED_DATE;
        }
        if (value.contains("-")) {
            formatter = DateTimeFormatter.ISO_LOCAL_DATE;
        }
        try {
            return LocalDate.parse(value, formatter);
        }
        catch (DateTimeParseException exception) {
            throw invalid(fieldName + " 형식이 올바르지 않습니다.");
        }
    }

    String pnu(String value) {
        if (value.length() != 19 || !value.chars().allMatch(Character::isDigit)) {
            throw invalid("PNU는 19자리 숫자여야 합니다.");
        }
        return value;
    }

    Integer nonNegative(Integer value, String fieldName) {
        if (value != null && value < 0) {
            throw invalid(fieldName + "는 음수일 수 없습니다.");
        }
        return value;
    }

    void validateApplicationPeriod(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw invalid("모집 종료일은 모집 시작일보다 빠를 수 없습니다.");
        }
    }

    String normalizedText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    MyHomeAnnouncementMappingRejectedException missing(String fieldName) {
        return new MyHomeAnnouncementMappingRejectedException(
                MyHomeAnnouncementMappingFailureReason.MISSING_REQUIRED_VALUE,
                fieldName + " 값이 없습니다."
        );
    }

    private MyHomeAnnouncementMappingRejectedException conflict(String fieldName) {
        return new MyHomeAnnouncementMappingRejectedException(
                MyHomeAnnouncementMappingFailureReason.CONFLICTING_SOURCE_VALUE,
                "같은 공고의 " + fieldName + " 값이 서로 다릅니다."
        );
    }

    MyHomeAnnouncementMappingRejectedException invalid(String detail) {
        return new MyHomeAnnouncementMappingRejectedException(
                MyHomeAnnouncementMappingFailureReason.INVALID_VALUE,
                detail
        );
    }
}
