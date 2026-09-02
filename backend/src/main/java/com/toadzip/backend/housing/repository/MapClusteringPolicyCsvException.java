package com.toadzip.backend.housing.repository;

import java.io.IOException;
import org.springframework.core.io.Resource;

final class MapClusteringPolicyCsvException {

    private MapClusteringPolicyCsvException() {
    }

    static IllegalStateException invalidHeader(Resource resource) {
        return new IllegalStateException("Invalid map clustering CSV header in " + resource.getDescription());
    }

    static IllegalStateException invalidColumnCount(Resource resource, int lineNumber, int expected) {
        return invalidRow(resource, lineNumber, "expected " + expected + " columns", null);
    }

    static IllegalStateException blankColumn(Resource resource, int lineNumber, String name) {
        return invalidRow(resource, lineNumber, "blank required column '" + name + "'", null);
    }

    static IllegalStateException inconsistentVersion(Resource resource, int lineNumber) {
        return invalidRow(resource, lineNumber, "policyVersion or regionDatasetVersion differs", null);
    }

    static IllegalStateException noData(Resource resource) {
        return new IllegalStateException("Map clustering CSV has no data rows: " + resource.getDescription());
    }

    static IllegalStateException invalidRow(Resource resource, int lineNumber, IllegalArgumentException cause) {
        return invalidRow(resource, lineNumber, cause.getMessage(), cause);
    }

    static IllegalStateException invalidPolicy(Resource resource, IllegalArgumentException cause) {
        return new IllegalStateException(
                "Invalid map clustering policy in " + resource.getDescription() + ": " + cause.getMessage(),
                cause
        );
    }

    static IllegalStateException readFailure(Resource resource, IOException cause) {
        return new IllegalStateException("Failed to read map clustering CSV: " + resource.getDescription(), cause);
    }

    private static IllegalStateException invalidRow(
            Resource resource,
            int lineNumber,
            String reason,
            Throwable cause
    ) {
        return new IllegalStateException(
                "Invalid map clustering CSV " + resource.getDescription() + " at line " + lineNumber + ": " + reason,
                cause
        );
    }
}
