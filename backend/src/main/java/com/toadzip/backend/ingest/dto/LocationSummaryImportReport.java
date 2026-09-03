package com.toadzip.backend.ingest.dto;

import java.util.List;

public record LocationSummaryImportReport(
        String sourceFileName,
        int textFileCount,
        long scannedRowCount,
        int targetRoadAddressCount,
        int matchedRoadAddressCount,
        int unmatchedRoadAddressCount,
        long storedLocationCount,
        long replacedRowCount,
        long invalidatedMappingCandidateCount,
        List<String> provinceCodes
) {

    public LocationSummaryImportReport {
        provinceCodes = List.copyOf(provinceCodes);
    }
}
