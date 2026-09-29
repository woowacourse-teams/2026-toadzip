package com.toadzip.backend.interest.service;

import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.interest.domain.NotificationInterestEvent;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.NotificationInterestRequest;
import com.toadzip.backend.interest.exception.InvalidNotificationInterestException;
import com.toadzip.backend.interest.repository.NotificationInterestRepository;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationInterestService {

    private final NotificationInterestRepository repository;
    private final RegionCodeResolver regionCodeResolver;
    private final HousingComplexRepository complexRepository;
    private final AnnouncementRepository announcementRepository;
    private final Clock clock;

    @Transactional
    public void record(NotificationInterestRequest request) {
        NotificationInterestEvent event = NotificationInterestEvent.create(
                request.eventId(), request.sessionId(), request.eventType(), request.source(),
                request.targetType(), request.targetId(), request.email(), clock.instant());
        if (!targetExists(request.targetType(), request.targetId())) {
            throw new InvalidNotificationInterestException();
        }
        repository.record(event);
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
