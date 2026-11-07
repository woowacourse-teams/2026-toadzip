package com.toadzip.backend.eligibility.domain;

import java.time.Instant;

public record StoredEligibilityProfile(long version, Instant updatedAt, EligibilityProfile profile) {
    public static StoredEligibilityProfile empty() {
        return new StoredEligibilityProfile(0, null, EligibilityProfile.empty());
    }
}
