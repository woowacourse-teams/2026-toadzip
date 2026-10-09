package com.toadzip.backend.interest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.interest.domain.NotificationEventSource;
import com.toadzip.backend.interest.domain.NotificationEventType;
import com.toadzip.backend.interest.domain.NotificationInterestOutcome;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.NotificationInterestRequest;
import com.toadzip.backend.interest.exception.InvalidNotificationInterestException;
import com.toadzip.backend.interest.repository.NotificationInterestRepository;
import com.toadzip.backend.privacy.domain.AnalyticsCollectionPolicy;
import com.toadzip.backend.privacy.domain.AnalyticsConsent;
import com.toadzip.backend.privacy.domain.ConsentDecision;
import com.toadzip.backend.privacy.exception.PrivacyException;
import com.toadzip.backend.privacy.repository.AnalyticsConsentRepository;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationInterestServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T09:00:00Z");
    private final NotificationInterestRepository repository = mock(NotificationInterestRepository.class);
    private final AnalyticsConsentRepository consents = mock(AnalyticsConsentRepository.class);
    private final PrivacyNoticeCatalog notices = mock(PrivacyNoticeCatalog.class);

    @Test
    void 미동의_클릭_분석을_저장하지_않는다() {
        NotificationInterestService service = service();
        assertThrows(PrivacyException.class, () -> service.record(request(NotificationEventType.CLICKED), null, null));
        verify(repository, never()).record(any());
    }

    @Test
    void 허용된_클릭도_업무_설정을_생성하지_않고_관찰_결과만_기록한다() {
        AnalyticsConsent consent = AnalyticsConsent.restore(UUID.randomUUID(), null, ConsentDecision.GRANTED,
                "notice", "scope", NOW, NOW.plusSeconds(60), 1, NOW, NOW);
        when(consents.lockForCollection(1L, null)).thenReturn(consent);
        when(notices.requiredAnalyticsScope()).thenReturn("scope");
        when(repository.record(any())).thenReturn(true);

        assertEquals(NotificationInterestOutcome.OBSERVED,
                service().record(request(NotificationEventType.CLICKED), 1L, null).outcome());
        verify(repository).complete(any());
    }

    @Test
    void 거부한_회원의_관찰을_거절한다() {
        AnalyticsConsent consent = AnalyticsConsent.restore(UUID.randomUUID(), null, ConsentDecision.DENIED,
                "notice", "scope", NOW, null, 1, NOW, NOW);
        when(consents.lockForCollection(1L, null)).thenReturn(consent);
        when(notices.requiredAnalyticsScope()).thenReturn("scope");
        assertThrows(PrivacyException.class,
                () -> service().record(request(NotificationEventType.EXPOSED), 1L, null));
        verify(repository, never()).record(any());
    }

    @Test
    void 공개_이벤트는_신청과_취소를_받지_않는다() {
        for (NotificationEventType type : java.util.List.of(
                NotificationEventType.CONFIRMED, NotificationEventType.CANCELLED)) {
            assertThrows(InvalidNotificationInterestException.class,
                    () -> service().record(request(type), 1L, null));
        }
        verify(repository, never()).record(any());
    }

    @Test
    void 동의잠금_대기중_만료되면_잠금획득_시각을_기준으로_차단한다() {
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
        NotificationInterestService service = new NotificationInterestService(
                repository, consents, notices, clock, new AnalyticsCollectionPolicy());

        PrivacyException failure = assertThrows(PrivacyException.class,
                () -> service.record(request(NotificationEventType.CLICKED), 1L, null));

        assertEquals("ANALYTICS_CONSENT_REQUIRED", failure.getCode());
        verify(repository, never()).record(any());
    }

    private NotificationInterestService service() {
        return new NotificationInterestService(repository, consents, notices, Clock.fixed(NOW, ZoneOffset.UTC),
                new AnalyticsCollectionPolicy());
    }

    private NotificationInterestRequest request(NotificationEventType eventType) {
        return new NotificationInterestRequest(UUID.randomUUID(), UUID.randomUUID(), eventType,
                NotificationEventSource.REGION_SEARCH, NotificationTargetType.REGION, "11", null, null);
    }
}
