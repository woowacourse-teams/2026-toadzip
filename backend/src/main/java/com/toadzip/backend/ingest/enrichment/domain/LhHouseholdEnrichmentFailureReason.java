package com.toadzip.backend.ingest.enrichment.domain;

public enum LhHouseholdEnrichmentFailureReason {
    INVALID_SOURCE,
    COMPLEX_NOT_FOUND,
    AMBIGUOUS_COMPLEX,
    DUPLICATE_TARGET_COMPLEX
}
