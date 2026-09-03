package com.toadzip.backend.ingest.repository.external;

import java.util.Set;

public record LocationSummaryFileParseResult(
        int entryCount,
        long rowCount,
        long coordinateRowCount,
        Set<String> entryNames,
        Set<String> provinceCodes
) {

    public LocationSummaryFileParseResult {
        entryNames = Set.copyOf(entryNames);
        provinceCodes = Set.copyOf(provinceCodes);
    }

    public long missingCoordinateRowCount() {
        return rowCount - coordinateRowCount;
    }
}
