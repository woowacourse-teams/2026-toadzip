package com.toadzip.backend.privacy.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.privacy.exception.PrivacyException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AnalyticsCollectionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-09T09:00:00Z");
    private static final String SCOPE = "analytics-scope-2";
    private final AnalyticsCollectionPolicy policy = new AnalyticsCollectionPolicy();

    @Test
    void 현재_범위에_유효하게_허용한_선택으로_수집할_수_있다() {
        AnalyticsConsent consent = consent(ConsentDecision.GRANTED, SCOPE, NOW.plusSeconds(60));
        assertTrue(policy.isAllowed(consent, SCOPE, NOW));
        assertDoesNotThrow(() -> policy.requireAllowed(consent, SCOPE, NOW));
    }

    @Test
    void 동의_기록이_없거나_만료시각이_없는_허용은_수집하지_않는다() {
        assertRejected(null, NOW);
        assertRejected(consent(ConsentDecision.GRANTED, SCOPE, null), NOW);
    }

    @ParameterizedTest
    @EnumSource(value = ConsentDecision.class, names = {"UNSET", "DENIED", "WITHDRAWN"})
    void 미선택_거부_철회는_수집하지_않는다(ConsentDecision decision) {
        assertRejected(consent(decision, SCOPE, NOW.plusSeconds(60)), NOW);
    }

    @Test
    void 이전_익명_안내의_허용은_새_수집범위에_사용하지_않는다() {
        assertRejected(consent(ConsentDecision.GRANTED, "analytics-scope-1", NOW.plusSeconds(60)), NOW);
    }

    @Test
    void 만료_직전까지_허용하고_정각부터_차단한다() {
        Instant expiresAt = NOW.plusSeconds(60);
        AnalyticsConsent consent = consent(ConsentDecision.GRANTED, SCOPE, expiresAt);
        assertTrue(policy.isAllowed(consent, SCOPE, expiresAt.minusNanos(1)));
        assertRejected(consent, expiresAt);
        assertRejected(consent, expiresAt.plusNanos(1));
    }

    private void assertRejected(AnalyticsConsent consent, Instant now) {
        assertFalse(policy.isAllowed(consent, SCOPE, now));
        PrivacyException failure = assertThrows(PrivacyException.class,
                () -> policy.requireAllowed(consent, SCOPE, now));
        assertEquals("ANALYTICS_CONSENT_REQUIRED", failure.getCode());
    }

    private AnalyticsConsent consent(ConsentDecision decision, String scope, Instant expiresAt) {
        return AnalyticsConsent.restore(UUID.randomUUID(), null, decision, "analytics-2026-10-09-v2", scope,
                NOW, expiresAt, 1, NOW, NOW);
    }
}
