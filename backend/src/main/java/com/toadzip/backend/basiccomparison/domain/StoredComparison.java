package com.toadzip.backend.basiccomparison.domain;

import java.time.Instant;

public record StoredComparison(
        long id,
        long announcementId,
        String status,
        long revision,
        long version,
        int schemaVersion,
        String content,
        Instant updatedAt,
        Instant publishedAt) {}
