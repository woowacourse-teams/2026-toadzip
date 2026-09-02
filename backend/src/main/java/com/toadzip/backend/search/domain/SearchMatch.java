package com.toadzip.backend.search.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public record SearchMatch(String normalizedQuery, List<String> tokens) {

    public static SearchMatch from(String query) {
        if (query == null) {
            throw new IllegalArgumentException("검색어가 필요하다.");
        }
        String normalized = query.strip().replaceAll("\\s+", " ");
        if (normalized.replace(" ", "").length() < 2 || normalized.length() > 50) {
            throw new IllegalArgumentException("검색어는 공백 제외 2자 이상 50자 이하여야 한다.");
        }
        return new SearchMatch(
                normalized,
                Arrays.stream(normalized.split(" "))
                        .map(token -> token.toLowerCase(Locale.ROOT))
                        .toList()
        );
    }

    public boolean matches(String... fields) {
        List<String> normalizedFields = normalizedFields(fields);
        return tokens.stream().allMatch(token -> normalizedFields.stream().anyMatch(field -> field.contains(token)));
    }

    public int rank(String... fields) {
        List<String> normalizedFields = normalizedFields(fields);
        String query = normalizedQuery.toLowerCase(Locale.ROOT);
        if (normalizedFields.stream().anyMatch(field -> field.equals(query))) {
            return 0;
        }
        if (normalizedFields.stream().anyMatch(field -> field.startsWith(query))) {
            return 1;
        }
        return 2;
    }

    private List<String> normalizedFields(String... fields) {
        return Arrays.stream(fields)
                .filter(java.util.Objects::nonNull)
                .map(field -> field.toLowerCase(Locale.ROOT))
                .toList();
    }
}
