package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.collection.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

class MyHomeComplexValueParser {

    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DOTTED_DATE = DateTimeFormatter.ofPattern("uuuu.MM.dd")
            .withResolverStyle(ResolverStyle.STRICT);

    String requiredText(
            List<MyHomeComplexSource> sources,
            Function<MyHomeComplexSource, String> extractor,
            String fieldName
    ) {
        Set<String> values = textValues(sources, extractor);
        if (values.isEmpty()) {
            throw missing(fieldName);
        }
        if (values.size() > 1) {
            throw conflict(fieldName);
        }
        return values.iterator().next();
    }

    String optionalText(
            List<MyHomeComplexSource> sources,
            Function<MyHomeComplexSource, String> extractor,
            String fieldName
    ) {
        Set<String> values = textValues(sources, extractor);
        if (values.size() > 1) {
            throw conflict(fieldName);
        }
        return values.stream().findFirst().orElse(null);
    }

    private Set<String> textValues(
            List<MyHomeComplexSource> sources,
            Function<MyHomeComplexSource, String> extractor
    ) {
        Set<String> values = new LinkedHashSet<>();
        for (MyHomeComplexSource source : sources) {
            String value = normalizedText(extractor.apply(source));
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    int requiredNonNegativeInteger(
            List<MyHomeComplexSource> sources,
            Function<MyHomeComplexSource, Integer> extractor,
            String fieldName
    ) {
        Set<Integer> values = new LinkedHashSet<>();
        for (MyHomeComplexSource source : sources) {
            Integer value = extractor.apply(source);
            if (value != null) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            throw missing(fieldName);
        }
        if (values.size() > 1) {
            throw conflict(fieldName);
        }
        int value = values.iterator().next();
        if (value < 0) {
            throw invalid(fieldName + "은 음수일 수 없습니다.");
        }
        return value;
    }

    String requiredText(String value, String fieldName) {
        String normalized = normalizedText(value);
        if (normalized == null) {
            throw missing(fieldName);
        }
        return normalized;
    }

    BigDecimal requiredArea(BigDecimal value, String fieldName) {
        if (value == null) {
            throw missing(fieldName);
        }
        return normalizedArea(value, fieldName);
    }

    BigDecimal optionalArea(BigDecimal value, String fieldName) {
        if (value == null) {
            return null;
        }
        return normalizedArea(value, fieldName);
    }

    private BigDecimal normalizedArea(BigDecimal value, String fieldName) {
        if (value.signum() < 0) {
            throw invalid(fieldName + "은 음수일 수 없습니다.");
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    String pnu(String value) {
        if (value.length() != 19 || !value.chars().allMatch(Character::isDigit)) {
            throw invalid("PNU는 19자리 숫자여야 합니다.");
        }
        return value;
    }

    String provinceCode(String value) {
        if (value.length() != 2 || !value.chars().allMatch(Character::isDigit)) {
            throw invalid("시·도 코드는 2자리 숫자여야 합니다.");
        }
        return value;
    }

    String districtCode(String provinceCode, String districtCode) {
        String normalized = districtCode.length() == 3 ? provinceCode + districtCode : districtCode;
        if (normalized.length() != 5
                || !normalized.startsWith(provinceCode)
                || !normalized.chars().allMatch(Character::isDigit)) {
            throw invalid("시·군·구 코드는 시·도 코드를 포함한 5자리 숫자여야 합니다.");
        }
        return normalized;
    }

    LocalDate optionalDate(String value) {
        if (value == null) {
            return null;
        }
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
            throw invalid("준공일 형식이 올바르지 않습니다.");
        }
    }

    String normalizedText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    MyHomeComplexMappingRejectedException missing(String fieldName) {
        return new MyHomeComplexMappingRejectedException(
                MyHomeComplexMappingFailureReason.MISSING_REQUIRED_VALUE,
                fieldName + " 값이 없습니다."
        );
    }

    private MyHomeComplexMappingRejectedException conflict(String fieldName) {
        return new MyHomeComplexMappingRejectedException(
                MyHomeComplexMappingFailureReason.CONFLICTING_SOURCE_VALUE,
                "같은 단지의 " + fieldName + " 값이 서로 다릅니다."
        );
    }

    MyHomeComplexMappingRejectedException invalid(String detail) {
        return new MyHomeComplexMappingRejectedException(
                MyHomeComplexMappingFailureReason.INVALID_VALUE,
                detail
        );
    }
}
