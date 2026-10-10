package com.toadzip.backend.privacy.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.privacy.controller.PrivacyCookieSupport;
import com.toadzip.backend.privacy.exception.PrivacyException;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PrivacyNoticeCatalogTest {

    @Test
    void 현재_세_안내는_원문과_버전_메타데이터로_연결된다() {
        PrivacyNoticeCatalog catalog = new PrivacyNoticeCatalog();
        assertEquals(List.of("ANALYTICS_NOTICE", "NOTIFICATION_NOTICE", "PRIVACY_POLICY"),
                catalog.currentDocuments().stream().map(PrivacyNotice::key).toList());
        for (PrivacyNotice current : catalog.currentDocuments()) {
            PrivacyNotice document = catalog.find(current.key(), current.version());
            assertNotNull(document.effectiveAt());
            assertEquals(PrivacyHash.sha256(document.content()), document.contentHash());
            assertTrue(document.content().contains("90일"));
        }
        assertEquals("privacy-scope-1", catalog.current("PRIVACY_POLICY").scopeVersion());
        assertEquals("analytics-scope-2", catalog.requiredAnalyticsScope());
        assertEquals("analytics-2026-10-09-v2", catalog.currentVersion("ANALYTICS_NOTICE"));
        assertEquals("analytics-scope-1", catalog.find("ANALYTICS_NOTICE", "analytics-2026-10-09-v1").scopeVersion());
        assertThrows(PrivacyException.class, () -> catalog.find("PRIVACY_POLICY", "unknown"));
    }

    @Test
    void 운영_쿠키는_host_접두사와_필수_속성을_가지고_방문으로_만료를_늘리지_않는다() {
        PrivacyCookieSupport cookies = new PrivacyCookieSupport(true, new MockEnvironment());
        Instant now = Instant.parse("2026-10-09T09:00:00Z");
        String header = cookies.header("test-token", now, now.plusSeconds(123));
        assertTrue(header.startsWith("__Host-toadzip-privacy="));
        assertTrue(header.contains("Secure"));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Lax"));
        assertTrue(header.contains("Path=/"));
        assertTrue(header.contains("Max-Age=123"));
        assertTrue(cookies.header("test-token", now.plusSeconds(23), now.plusSeconds(123))
                .contains("Max-Age=100"));
    }

    @Test
    void 운영에서_비보안_쿠키를_설정하면_시작을_실패시킨다() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new PrivacyCookieSupport(false, environment));
    }
}
