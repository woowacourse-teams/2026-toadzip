package com.toadzip.backend.user.domain;

import com.toadzip.backend.privacy.domain.PrivacyRetentionPolicy;
import java.io.Serializable;
import java.time.Instant;

public record SocialAuthorizationContext(String policyVersion, Instant issuedAt) implements Serializable {

    public boolean isValidAt(Instant now) {
        return policyVersion != null && !policyVersion.isBlank() && issuedAt != null
                && !issuedAt.isAfter(now)
                && issuedAt.plus(PrivacyRetentionPolicy.OAUTH_AUTHORIZATION_REQUEST_LIFETIME).isAfter(now);
    }
}
