package com.toadzip.backend.ingest.mapping.domain;

public enum MyHomeComplexMappingFailureReason {
    MISSING_REQUIRED_VALUE,
    INVALID_VALUE,
    CONFLICTING_SOURCE_VALUE,
    GEOCODING_ERROR,
    PERSISTENCE_ERROR
}
