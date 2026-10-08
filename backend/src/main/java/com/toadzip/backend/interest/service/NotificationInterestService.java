package com.toadzip.backend.interest.service;

import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.interest.domain.NotificationInterestEvent;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.NotificationInterestRequest;
import com.toadzip.backend.interest.dto.NotificationSubscriptionResponse;
import com.toadzip.backend.interest.exception.InvalidNotificationInterestException;
import com.toadzip.backend.interest.repository.NotificationInterestRepository;
import com.toadzip.backend.interest.repository.NotificationGuestSubscriptionRepository;
import com.toadzip.backend.interest.repository.NotificationSubscriptionRepository;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.time.Clock;
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
    public void record(NotificationInterestRequest request, Long userId) {
        NotificationInterestEvent event = NotificationInterestEvent.create(
                request.eventId(), request.sessionId(), request.eventType(), request.source(),
                request.targetType(), request.targetId(), request.email(), clock.instant());
        if (request.eventType() != com.toadzip.backend.interest.domain.NotificationEventType.CANCELLED
                && !targetExists(request.targetType(), request.targetId())) {
            throw new InvalidNotificationInterestException();
        }
        if (!repository.record(event)) {
            return;
        }
        if (userId == null) {
            updateGuest(request, event.getCreatedAt());
            return;
        }
        switch (request.eventType()) {
            case CONFIRMED -> {
                if (request.email() == null) {
                    subscriptionRepository.activate(userId, request.targetType(), request.targetId(),
                            event.getCreatedAt());
                    return;
                }
                if (request.email() != null) {
                    subscriptionRepository.confirm(userId, request.targetType(), request.targetId(),
                            request.email(), event.getCreatedAt());
                }
            }
            case CLICKED -> {
                if (subscriptionRepository.hasEmail(userId)) {
                    subscriptionRepository.activate(userId, request.targetType(), request.targetId(),
                            event.getCreatedAt());
                }
            }
            case CANCELLED -> subscriptionRepository.cancel(userId, request.targetType(), request.targetId(),
                    event.getCreatedAt());
            case EXPOSED, DECLINED -> { }
        }
    }

    private void updateGuest(NotificationInterestRequest request, java.time.Instant now) {
        UUID clientId = request.clientId();
        if (clientId == null) {
            return;
        }
        switch (request.eventType()) {
            case CONFIRMED -> {
                if (request.email() != null) {
                    guestSubscriptionRepository.confirm(clientId, request.targetType(), request.targetId(),
                            request.email(), now);
                }
            }
            case CLICKED -> {
                if (guestSubscriptionRepository.hasEmail(clientId)) {
                    guestSubscriptionRepository.activate(clientId, request.targetType(), request.targetId(), now);
                }
            }
            case CANCELLED -> guestSubscriptionRepository.cancel(clientId, request.targetType(), request.targetId(), now);
            case EXPOSED, DECLINED -> { }
        }
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
