package com.toadzip.backend.ingest.location.repository.external;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public record LocationSummaryFileParseResult(
        int entryCount,
        long rowCount,
        Map<String, Set<String>> provinceCodesByEntry,
        Set<String> provinceCodes
) {

    public LocationSummaryFileParseResult {
        provinceCodesByEntry = immutableProvinceCodesByEntry(provinceCodesByEntry);
        provinceCodes = Set.copyOf(provinceCodes);
    }

    private static Map<String, Set<String>> immutableProvinceCodesByEntry(
            Map<String, Set<String>> provinceCodesByEntry
    ) {
        Map<String, Set<String>> copied = new HashMap<>();
        provinceCodesByEntry.forEach((entryName, provinceCodes) ->
                copied.put(entryName, Set.copyOf(provinceCodes))
        );
        return Map.copyOf(copied);
    }
}
