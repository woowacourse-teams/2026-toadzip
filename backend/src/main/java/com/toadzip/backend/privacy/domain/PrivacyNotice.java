package com.toadzip.backend.privacy.domain;

import java.time.Instant;

public record PrivacyNotice(String key, String version, String scopeVersion, Instant effectiveAt,
        String contentHash, String content) {
}
