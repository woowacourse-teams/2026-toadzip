package com.toadzip.backend.privacy.dto;

import java.time.Instant;

public record PrivacyNoticeResponse(String key, String version, String scopeVersion, Instant effectiveAt,
        String contentHash, String documentUrl, String format, String content) {
}
