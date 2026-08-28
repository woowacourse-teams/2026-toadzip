package com.toadzip.backend.ingest.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

final class MyHomeSupplyNameNormalizer {

    private static final List<String> COMPLEX_NOISE_WORDS = List.of(
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

    private MyHomeSupplyNameNormalizer() {
    }

    static String complexName(String value) {
        String normalized = alphanumeric(value).replace("블록", "bl");
        for (String noiseWord : COMPLEX_NOISE_WORDS) {
            normalized = normalized.replace(noiseWord, "");
        }
        return normalized;
    }

    static String housingTypeName(String value) {
        return removeSuffixes(alphanumeric(value), HOUSING_TYPE_SUFFIXES);
    }

    static boolean sameComplex(String left, String right) {
        String normalizedLeft = complexName(left);
        String normalizedRight = complexName(right);
        if (normalizedLeft.length() < 4 || normalizedRight.length() < 4) {
            return normalizedLeft.equals(normalizedRight);
        }
        return normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft);
    }

    private static String alphanumeric(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        normalized.codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(result::appendCodePoint);
        return result.toString();
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
