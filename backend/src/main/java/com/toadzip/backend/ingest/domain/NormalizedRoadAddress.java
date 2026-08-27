package com.toadzip.backend.ingest.domain;

import java.text.Normalizer;
import java.util.regex.Pattern;

public record NormalizedRoadAddress(String value) {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Pattern HYPHEN_SPACING = Pattern.compile("\\s*-\\s*");

    private static final Pattern TRAILING_REFERENCE = Pattern.compile("\\s*\\([^()]*\\)\\s*$");

    public NormalizedRoadAddress {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("도로명주소는 필수입니다.");
        }
        value = normalize(value);
    }

    public String withoutReference() {
        return TRAILING_REFERENCE.matcher(value).replaceFirst("").strip();
    }

    public boolean matches(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        NormalizedRoadAddress normalizedCandidate = new NormalizedRoadAddress(candidate);
        return value.equals(normalizedCandidate.value)
                || withoutReference().equals(normalizedCandidate.withoutReference());
    }

    private static String normalize(String raw) {
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).strip();
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ");
        return HYPHEN_SPACING.matcher(normalized).replaceAll("-");
    }
}
