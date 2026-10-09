package com.toadzip.backend.user.domain;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

public record SocialAuthorizationContext(String policyVersion, Instant issuedAt) implements Serializable {

    private static final Duration REQUEST_LIFETIME = Duration.ofMinutes(10);

    public boolean isValidAt(Instant now) {
        return issuedAt != null
                && !issuedAt.isAfter(now)
                && issuedAt.plus(REQUEST_LIFETIME).isAfter(now);
    }
}
