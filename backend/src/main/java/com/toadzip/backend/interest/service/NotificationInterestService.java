package com.toadzip.backend.interest.service;

import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.interest.domain.NotificationEventType;
import com.toadzip.backend.interest.domain.NotificationInterestEvent;
import com.toadzip.backend.interest.domain.NotificationInterestOutcome;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.NotificationInterestRequest;
import com.toadzip.backend.interest.dto.NotificationInterestResponse;
import com.toadzip.backend.interest.dto.NotificationSubscriptionResponse;
import com.toadzip.backend.interest.exception.InvalidNotificationInterestException;
import com.toadzip.backend.interest.repository.NotificationGuestSubscriptionRepository;
import com.toadzip.backend.interest.repository.NotificationInterestRepository;
import com.toadzip.backend.interest.repository.NotificationSubscriptionRepository;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationInterestService {

    private final NotificationInterestRepository repository;
    private final NotificationSubscriptionRepository subscriptionRepository;
    private final NotificationGuestSubscriptionRepository guestSubscriptionRepository;
    private final RegionCodeResolver regionCodeResolver;
    private final HousingComplexRepository complexRepository;
    private final AnnouncementRepository announcementRepository;
    private final Clock clock;

    @Transactional
    public NotificationInterestResponse record(NotificationInterestRequest request, Long userId) {
        NotificationInterestEvent event = NotificationInterestEvent.forRequest(
                request.eventId(), request.sessionId(), request.eventType(), request.source(),
                request.targetType(), request.targetId(), request.email(), clock.instant(), userId, request.clientId());
        if (!repository.record(event)) {
            NotificationInterestEvent recorded = repository.findForUpdate(event.getEventId());
            recorded.verifySameRequest(event);
            return response(recorded);
        }
        if (request.eventType() != NotificationEventType.CANCELLED
                && !targetExists(request.targetType(), request.targetId())) {
            throw new InvalidNotificationInterestException();
        }
        event.complete(updateSubscription(request, userId, event.getCreatedAt()));
        repository.complete(event);
        return response(event);
    }

    private NotificationInterestResponse response(NotificationInterestEvent event) {
        return new NotificationInterestResponse(event.getEventId(), event.getTargetType(), event.getTargetId(),
                event.getOutcome(), event.getCreatedAt());
    }

    private NotificationInterestOutcome updateSubscription(
            NotificationInterestRequest request, Long userId, Instant now) {
        if (userId == null) {
            return updateGuest(request, now);
        }
        if (request.eventType() == NotificationEventType.CONFIRMED) {
            if (request.email() == null) {
                return subscriptionRepository.activateForMember(
                        userId, request.targetType(), request.targetId(), now);
            }
            return subscriptionRepository.confirm(
                    userId, request.targetType(), request.targetId(), request.email(), now);
        }
        if (request.eventType() == NotificationEventType.CLICKED
                && subscriptionRepository.hasEmail(userId)) {
            return subscriptionRepository.activate(userId, request.targetType(), request.targetId(), now);
        }
        return switch (request.eventType()) {
            case CANCELLED -> subscriptionRepository.cancel(userId, request.targetType(), request.targetId(), now);
            case EXPOSED, DECLINED -> NotificationInterestOutcome.OBSERVED;
            case CLICKED, CONFIRMED -> NotificationInterestOutcome.NOT_ACTIVATED;
        };
    }

    private NotificationInterestOutcome updateGuest(NotificationInterestRequest request, Instant now) {
        UUID clientId = request.clientId();
        if (clientId == null) {
            return switch (request.eventType()) {
                case CANCELLED -> NotificationInterestOutcome.UNCHANGED;
                case EXPOSED, DECLINED -> NotificationInterestOutcome.OBSERVED;
                case CLICKED, CONFIRMED -> NotificationInterestOutcome.NOT_ACTIVATED;
            };
        }
        if (request.eventType() == NotificationEventType.CONFIRMED
                && request.email() != null) {
            return guestSubscriptionRepository.confirm(clientId, request.targetType(), request.targetId(),
                    request.email(), now);
        }
        if (request.eventType() == NotificationEventType.CLICKED
                && guestSubscriptionRepository.hasEmail(clientId)) {
            return guestSubscriptionRepository.activate(clientId, request.targetType(), request.targetId(), now);
        }
        return switch (request.eventType()) {
            case CANCELLED -> guestSubscriptionRepository.cancel(
                    clientId, request.targetType(), request.targetId(), now);
            case EXPOSED, DECLINED -> NotificationInterestOutcome.OBSERVED;
            case CLICKED, CONFIRMED -> NotificationInterestOutcome.NOT_ACTIVATED;
        };
    }

    @Transactional(readOnly = true)
    public NotificationSubscriptionResponse findForUser(long userId) {
        return subscriptionRepository.findForUser(userId);
    }

    @Transactional(readOnly = true)
    public NotificationSubscriptionResponse findForClient(UUID clientId) {
        return guestSubscriptionRepository.findForClient(clientId);
    }

    private boolean targetExists(NotificationTargetType type, String id) {
        if (type == NotificationTargetType.REGION) {
            return regionCodeResolver.filterCodes(id).isPresent();
        }
        long numericId = numericId(id);
        if (numericId <= 0) {
            return false;
        }
        if (type == NotificationTargetType.COMPLEX) {
            return complexRepository.existsById(numericId);
        }
        return announcementRepository.existsById(numericId);
    }

    private long numericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw new InvalidNotificationInterestException();
        }
    }
}
