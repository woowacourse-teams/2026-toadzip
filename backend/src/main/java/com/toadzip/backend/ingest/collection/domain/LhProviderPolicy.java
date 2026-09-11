package com.toadzip.backend.ingest.collection.domain;

import java.util.Locale;

public final class LhProviderPolicy {

    private static final String KOREA_LAND_AND_HOUSING_CORPORATION = "한국토지주택공사";

    private LhProviderPolicy() {
    }

    public static boolean isLh(String provider) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        String normalized = provider.strip();
        return normalized.toUpperCase(Locale.ROOT).startsWith("LH")
                || KOREA_LAND_AND_HOUSING_CORPORATION.equals(normalized);
    }
}
