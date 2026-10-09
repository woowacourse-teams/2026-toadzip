package com.toadzip.backend.privacy.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalyticsConsentTest {

    private final PrivacyRetentionPolicy retention = new PrivacyRetentionPolicy();
    private final Instant now = Instant.parse("2026-10-09T09:00:00Z");

    @Test
    void 만료_직전은_허용이고_정각과_직후는_만료다() {
        AnalyticsConsent consent = consent(ConsentDecision.UNSET, false);
        consent.choose(ConsentAction.GRANT, "v1", "scope-1", now, retention);
        Instant expiry = now.plus(PrivacyRetentionPolicy.ANALYTICS_GRANT_VALIDITY);
        assertEquals(ConsentStatus.GRANTED, consent.effectiveStatus("scope-1", expiry.minusNanos(1)));
        assertEquals(ConsentStatus.EXPIRED, consent.effectiveStatus("scope-1", expiry));
        assertEquals(ConsentStatus.EXPIRED, consent.effectiveStatus("scope-1", expiry.plusNanos(1)));
    }

    @Test
    void 범위가_바뀌면_허용만_재동의를_요구하고_거부는_유지한다() {
        AnalyticsConsent granted = consent(ConsentDecision.GRANTED, false);
        assertEquals(ConsentStatus.RECONSENT_REQUIRED, granted.effectiveStatus("scope-2", now));
        AnalyticsConsent denied = consent(ConsentDecision.DENIED, false);
        assertEquals(ConsentStatus.DENIED, denied.effectiveStatus("scope-2", now));
    }

    @Test
    void 허용_전_철회는_거부이고_허용_후_거부는_철회다() {
        AnalyticsConsent consent = consent(ConsentDecision.UNSET, false);
        consent.choose(ConsentAction.WITHDRAW, "v1", "scope-1", now, retention);
        assertEquals(ConsentDecision.DENIED, consent.getDecision());
        assertNull(consent.getExpiresAt());
        consent.choose(ConsentAction.GRANT, "v1", "scope-1", now, retention);
        consent.choose(ConsentAction.DENY, "v1", "scope-1", now, retention);
        assertEquals(ConsentDecision.WITHDRAWN, consent.getDecision());
        assertEquals(3, consent.getRevision());
        assertNull(consent.getExpiresAt());
    }

    @Test
    void 비회원의_거부도_180일후_만료한다() {
        AnalyticsConsent consent = consent(ConsentDecision.UNSET, true);
        consent.choose(ConsentAction.DENY, "v1", "scope-1", now, retention);
        assertEquals(now.plus(PrivacyRetentionPolicy.GUEST_CHOICE_VALIDITY), consent.getExpiresAt());
    }

    @Test
    void 알림_12개월은_서울_달력의_윤년_월말을_따른다() {
        Instant leapDay = Instant.parse("2024-02-29T14:59:59Z");
        assertEquals(Instant.parse("2025-02-28T14:59:59Z"), retention.notificationExpiresAt(leapDay));
    }

    private AnalyticsConsent consent(ConsentDecision decision, boolean guest) {
        String hash = null;
        if (guest) {
            hash = "a".repeat(64);
        }
        return AnalyticsConsent.restore(UUID.randomUUID(), hash, decision, "v1", "scope-1", now, null, 0, now, now);
    }
}
