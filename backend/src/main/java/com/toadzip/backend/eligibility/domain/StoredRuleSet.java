package com.toadzip.backend.eligibility.domain;

import java.time.Instant;

public record StoredRuleSet(
        long id,
        long announcementId,
        String status,
        long version,
        long revision,
        RuleSetMetadata metadata,
        Instant updatedAt,
        Instant publishedAt) {}
