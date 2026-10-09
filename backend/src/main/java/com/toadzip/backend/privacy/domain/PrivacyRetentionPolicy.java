package com.toadzip.backend.privacy.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

public class PrivacyRetentionPolicy {

    public static final Duration ANALYTICS_GRANT_VALIDITY = Duration.ofDays(180);
    public static final Duration GUEST_CHOICE_VALIDITY = Duration.ofDays(180);
    public static final Duration GUEST_CONTEXT_PREPARATION_LIFETIME = Duration.ofHours(24);
    public static final Duration SUPERSEDED_CONSENT_HISTORY_RETENTION = Duration.ofDays(90);
    public static final Duration INACTIVE_NOTIFICATION_RETENTION = Duration.ofDays(90);
    public static final Duration NOTIFICATION_EVENT_RETENTION = Duration.ofDays(90);
    public static final Duration PRIVACY_REQUEST_RECORD_RETENTION = Duration.ofDays(90);
    public static final Duration PERSONAL_DATA_BACKUP_RETENTION = Duration.ofDays(7);
    public static final int NOTIFICATION_SUBSCRIPTION_VALIDITY_MONTHS = 12;
    public static final int ANONYMOUS_AGGREGATE_RETENTION_MONTHS = 12;

    public Instant choiceExpiresAt(boolean guest, ConsentDecision decision, Instant now) {
        if (guest) {
            return now.plus(GUEST_CHOICE_VALIDITY);
        }
        if (decision == ConsentDecision.GRANTED) {
            return now.plus(ANALYTICS_GRANT_VALIDITY);
        }
        return null;
    }

    public Instant consentPurgeAfter(Instant expiresAt) {
        if (expiresAt == null) {
            return null;
        }
        return expiresAt.plus(SUPERSEDED_CONSENT_HISTORY_RETENTION);
    }

    public Instant notificationExpiresAt(Instant requestedAt) {
        return requestedAt.atZone(ZoneId.of("Asia/Seoul"))
                .plusMonths(NOTIFICATION_SUBSCRIPTION_VALIDITY_MONTHS).toInstant();
    }

    public Instant notificationPurgeAfter(Instant inactiveAt) {
        return inactiveAt.plus(INACTIVE_NOTIFICATION_RETENTION);
    }
}
