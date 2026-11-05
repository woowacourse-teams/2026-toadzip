package com.toadzip.backend.basiccomparison.dto;

import com.toadzip.backend.basiccomparison.domain.ComparisonContent;
import com.toadzip.backend.basiccomparison.domain.StoredComparison;

import java.time.Instant;

public final class ComparisonResponses {
    private ComparisonResponses() {}

    public record RuleSet(
            long id,
            long announcementId,
            String status,
            long revision,
            long version,
            int schemaVersion,
            Instant updatedAt,
            Instant publishedAt,
            ComparisonContent content) {
        public static RuleSet of(StoredComparison stored, ComparisonContent content) {
            return new RuleSet(
                    stored.id(),
                    stored.announcementId(),
                    stored.status(),
                    stored.revision(),
                    stored.version(),
                    stored.schemaVersion(),
                    stored.updatedAt(),
                    stored.publishedAt(),
                    content);
        }
    }
}
