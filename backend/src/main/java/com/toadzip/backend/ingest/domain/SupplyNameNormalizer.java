package com.toadzip.backend.ingest.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SupplyNameNormalizer {

    private static final List<String> COMPLEX_NOISE_WORDS = List.of(
            "통합공공임대주택",
            "국민임대주택",
            "국민임대",
            "영구임대주택",
            "영구임대",
            "행복주택",
            "통합공공임대",
            "휴먼시아",
            "아파트",
            "단지",
            "apt"
    );

    private static final List<String> HOUSING_TYPE_SUFFIXES = List.of("주택형", "타입", "type", "형");
    private static final Pattern NUMBER = Pattern.compile("[0-9]+");

    private SupplyNameNormalizer() {
    }

    public static String complexName(String value) {
        String normalized = alphanumeric(value).replace("블록", "bl");
        for (String noiseWord : COMPLEX_NOISE_WORDS) {
            normalized = normalized.replace(noiseWord, "");
        }
        return normalized;
    }

    public static String housingTypeName(String value) {
        return removeSuffixes(alphanumeric(value), HOUSING_TYPE_SUFFIXES);
    }

    public static boolean sameComplex(String left, String right) {
        String normalizedLeft = complexName(left);
        String normalizedRight = complexName(right);
        return !normalizedLeft.isEmpty() && normalizedLeft.equals(normalizedRight);
    }

    public static boolean compatibleComplex(String left, String right) {
        String normalizedLeft = canonicalNumbers(complexName(left));
        String normalizedRight = canonicalNumbers(complexName(right));
        if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) {
            return false;
        }
        if (!numbers(normalizedLeft).equals(numbers(normalizedRight))) {
            return false;
        }
        if (normalizedLeft.length() < 4 || normalizedRight.length() < 4) {
            return normalizedLeft.equals(normalizedRight);
        }
        return normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft);
    }

    public static boolean sameHousingType(String left, String right) {
        String normalizedLeft = housingTypeName(left);
        String normalizedRight = housingTypeName(right);
        return !normalizedLeft.isEmpty() && normalizedLeft.equals(normalizedRight);
    }

    private static String alphanumeric(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        normalized.codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    private static String canonicalNumbers(String value) {
        Matcher matcher = NUMBER.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, withoutLeadingZeros(matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static List<String> numbers(String value) {
        List<String> result = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(value);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private static String withoutLeadingZeros(String number) {
        int firstDigit = 0;
        while (firstDigit < number.length() - 1 && number.charAt(firstDigit) == '0') {
            firstDigit++;
        }
        return number.substring(firstDigit);
    }

    private static String removeSuffixes(String value, List<String> suffixes) {
        String normalized = value;
        boolean removed = true;
        while (removed) {
            removed = false;
            for (String suffix : suffixes) {
                if (!normalized.endsWith(suffix) || normalized.length() == suffix.length()) {
                    continue;
                }
                normalized = normalized.substring(0, normalized.length() - suffix.length());
                removed = true;
                break;
            }
        }
        return normalized;
    }
}
