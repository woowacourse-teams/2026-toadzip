package com.toadzip.backend.ingest.repository.external;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public record LocationSummaryFileParseResult(
        int entryCount,
        long rowCount,
        long coordinateRowCount,
        Set<String> entryNames,
        Map<String, Set<String>> provinceCodesByEntry,
        Set<String> provinceCodes
) {

    public LocationSummaryFileParseResult {
        entryNames = Set.copyOf(entryNames);
        provinceCodesByEntry = immutableProvinceCodesByEntry(provinceCodesByEntry);
        provinceCodes = Set.copyOf(provinceCodes);
    }

    public long missingCoordinateRowCount() {
        return rowCount - coordinateRowCount;
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
