package com.toadzip.backend.interest.service;

import com.toadzip.backend.interest.domain.NotificationEventType;
import com.toadzip.backend.interest.domain.NotificationInterestEvent;
import com.toadzip.backend.interest.domain.NotificationInterestOutcome;
import com.toadzip.backend.interest.dto.NotificationInterestRequest;
import com.toadzip.backend.interest.dto.NotificationInterestResponse;
import com.toadzip.backend.interest.exception.InvalidNotificationInterestException;
import com.toadzip.backend.interest.repository.NotificationInterestRepository;
import com.toadzip.backend.privacy.domain.AnalyticsCollectionPolicy;
import com.toadzip.backend.privacy.domain.AnalyticsConsent;
import com.toadzip.backend.privacy.repository.AnalyticsConsentRepository;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Optional behavioral observations never change notification settings. */
@Service
@RequiredArgsConstructor
public class NotificationInterestService {

    private final NotificationInterestRepository repository;
    private final AnalyticsConsentRepository consents;
    private final PrivacyNoticeCatalog notices;
    private final Clock clock;
    private final AnalyticsCollectionPolicy collectionPolicy;

    @Transactional
    public NotificationInterestResponse record(NotificationInterestRequest request, Long userId, String guestToken) {
        if (request.eventType() == NotificationEventType.CONFIRMED
                || request.eventType() == NotificationEventType.CANCELLED
                || request.email() != null || request.clientId() != null) {
            throw new InvalidNotificationInterestException();
        }
        AnalyticsConsent consent = consents.lockForCollection(userId, guestToken);
        Instant now = clock.instant();
        collectionPolicy.requireAllowed(consent, notices.requiredAnalyticsScope(), now);
        NotificationInterestEvent event = NotificationInterestEvent.forRequest(
                request.eventId(), request.sessionId(), request.eventType(), request.source(), request.targetType(),
                request.targetId(), null, now, null, null);
        if (!repository.record(event)) {
            NotificationInterestEvent recorded = repository.findForUpdate(event.getEventId());
            recorded.verifySameRequest(event);
            return response(recorded);
        }
        event.complete(NotificationInterestOutcome.OBSERVED);
        repository.complete(event);
        return response(event);
    }

    private NotificationInterestResponse response(NotificationInterestEvent event) {
        return new NotificationInterestResponse(event.getEventId(), event.getTargetType(), event.getTargetId(),
                event.getOutcome(), event.getCreatedAt());
    }
}
