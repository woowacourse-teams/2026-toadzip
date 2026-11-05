package com.toadzip.backend.basiccomparison.domain;

import java.time.Instant;

public record StoredComparisonProfile(long version, Instant updatedAt, ComparisonProfile profile) {
    public static StoredComparisonProfile empty() {
        return new StoredComparisonProfile(0, null, ComparisonProfile.empty());
    }
}
