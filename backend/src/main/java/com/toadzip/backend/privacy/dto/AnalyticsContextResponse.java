package com.toadzip.backend.privacy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

public record AnalyticsContextResponse(Subject subject, Consent consent, String requiredNoticeVersion,
        String requiredScopeVersion, boolean collectionAllowed, Instant checkedAt, int maxAgeSeconds) {

    public record Subject(String kind, @JsonInclude(JsonInclude.Include.NON_NULL) String userId, String contextId) {
    }

    public record Consent(String decision, String effectiveStatus, long revision, String noticeVersion,
            String scopeVersion, Instant decidedAt, Instant expiresAt) {
    }
}
