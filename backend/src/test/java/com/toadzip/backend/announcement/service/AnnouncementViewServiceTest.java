package com.toadzip.backend.announcement.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.repository.AnnouncementViewRepository;
import com.toadzip.backend.privacy.domain.AnalyticsCollectionPolicy;
import com.toadzip.backend.privacy.domain.AnalyticsConsent;
import com.toadzip.backend.privacy.domain.ConsentDecision;
import com.toadzip.backend.privacy.exception.PrivacyException;
import com.toadzip.backend.privacy.repository.AnalyticsConsentRepository;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AnnouncementViewServiceTest {

    @Test
    void 동의잠금_대기중_만료된_공고조회는_집계하지_않는다() {
        AnnouncementViewRepository views = mock(AnnouncementViewRepository.class);
        AnalyticsConsentRepository consents = mock(AnalyticsConsentRepository.class);
        PrivacyNoticeCatalog notices = mock(PrivacyNoticeCatalog.class);
        Instant expiry = Instant.parse("2026-10-09T09:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(expiry.minusSeconds(1));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> now.get());
        AnalyticsConsent consent = AnalyticsConsent.restore(UUID.randomUUID(), null, ConsentDecision.GRANTED,
                "notice", "scope", expiry.minusSeconds(100), expiry, 1,
                expiry.minusSeconds(100), expiry.minusSeconds(100));
        when(consents.lockForCollection(1L, null)).thenAnswer(invocation -> {
            now.set(expiry);
            return consent;
        });
        when(notices.requiredAnalyticsScope()).thenReturn("scope");
        AnalyticsCollectionPolicy policy = new AnalyticsCollectionPolicy();
        AnnouncementViewService service = new AnnouncementViewService(views, clock, consents, notices, policy);

        PrivacyException failure = assertThrows(PrivacyException.class,
                () -> service.recordView(1L, UUID.randomUUID(), 1L, null));

        assertEquals("ANALYTICS_CONSENT_REQUIRED", failure.getCode());
        verify(views, never()).lockViewCount(anyLong());
        verify(views, never()).increment(anyLong());
    }
}
