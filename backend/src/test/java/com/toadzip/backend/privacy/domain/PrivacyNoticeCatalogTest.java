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
        assertTrue(!catalog.current("NOTIFICATION_NOTICE").content().contains("준비 중"));
        assertTrue(!catalog.current("PRIVACY_POLICY").content().contains("알림 전달 기능은 준비 중"));
        assertTrue(catalog.find("NOTIFICATION_NOTICE", "notification-2026-10-10-v1").content().contains("준비 중"));
        assertEquals("privacy-scope-1", catalog.current("PRIVACY_POLICY").scopeVersion());
        assertEquals("privacy-2026-10-15-v1", catalog.currentVersion("PRIVACY_POLICY"));
        assertEquals("analytics-scope-3", catalog.requiredAnalyticsScope());
        assertEquals("analytics-2026-10-11-v1", catalog.currentVersion("ANALYTICS_NOTICE"));
        assertEquals("analytics-scope-1", catalog.find("ANALYTICS_NOTICE", "analytics-2026-10-09-v1").scopeVersion());
        assertEquals("analytics-scope-2", catalog.find("ANALYTICS_NOTICE", "analytics-2026-10-09-v2").scopeVersion());
        assertThrows(PrivacyException.class, () -> catalog.find("PRIVACY_POLICY", "unknown"));
    }

    @Test
    void 재동의_범위를_추가해도_과거_범위의_최초_무효화_시점은_연장하지_않는다() {
        PrivacyNoticeCatalog catalog = new PrivacyNoticeCatalog();
        Instant previousInvalidation = Instant.parse("2026-10-09T00:00:01Z");
        Instant currentInvalidation = Instant.parse("2026-10-10T15:00:00Z");

        assertEquals(previousInvalidation, catalog.scopeInvalidatedAt("analytics-scope-1"));
        assertEquals(currentInvalidation, catalog.scopeInvalidatedAt("analytics-scope-2"));
        assertEquals(currentInvalidation, catalog.analyticsScopeEffectiveAt());
        assertEquals(2, catalog.scopeInvalidations().size());
        assertEquals(previousInvalidation, catalog.scopeInvalidations().get("analytics-scope-1"));
        assertEquals(currentInvalidation, catalog.scopeInvalidations().get("analytics-scope-2"));
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
