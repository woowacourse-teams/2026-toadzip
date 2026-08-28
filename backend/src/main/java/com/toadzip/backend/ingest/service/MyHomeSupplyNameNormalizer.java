package com.toadzip.backend.ingest.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

final class MyHomeSupplyNameNormalizer {

    private static final List<String> COMPLEX_SUFFIXES = List.of("아파트", "단지", "apt");

    private static final List<String> HOUSING_TYPE_SUFFIXES = List.of("주택형", "타입", "type", "형");

    private MyHomeSupplyNameNormalizer() {
    }

    static String complexName(String value) {
        return removeSuffixes(alphanumeric(value), COMPLEX_SUFFIXES);
    }

    static String housingTypeName(String value) {
        return removeSuffixes(alphanumeric(value), HOUSING_TYPE_SUFFIXES);
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
