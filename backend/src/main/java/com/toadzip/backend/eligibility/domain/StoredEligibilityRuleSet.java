package com.toadzip.backend.eligibility.domain;

import java.time.Instant;

public record StoredEligibilityRuleSet(
        long id,
        long announcementId,
        String status,
        long revision,
        long version,
        int schemaVersion,
        String content,
        Instant updatedAt,
        Instant publishedAt) {}
