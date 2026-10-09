package com.toadzip.backend.interest.service;

import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.interest.domain.NotificationEventType;
import com.toadzip.backend.interest.domain.NotificationInterestOutcome;
import com.toadzip.backend.interest.domain.NotificationSettingCommand;
import com.toadzip.backend.interest.domain.NotificationSettingReceipt;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.MemberNotificationSettingsResponse;
import com.toadzip.backend.interest.dto.NotificationSettingsRequest;
import com.toadzip.backend.interest.dto.NotificationSettingsResponse;
import com.toadzip.backend.interest.dto.NotificationSettingsResponse.CurrentTarget;
import com.toadzip.backend.interest.exception.InvalidNotificationInterestException;
import com.toadzip.backend.interest.exception.NotificationInterestConflictException;
import com.toadzip.backend.interest.exception.NotificationSettingsConflictException;
import com.toadzip.backend.interest.repository.NotificationSubscriptionRepository;
import com.toadzip.backend.privacy.repository.PrivacyNotificationRepository;
import com.toadzip.backend.privacy.domain.PrivacyRetentionPolicy;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationSettingsService {

    private final PrivacyNotificationRepository repository;
    private final NotificationSubscriptionRepository subscriptions;
    private final PrivacyNoticeCatalog notices;
    private final RegionCodeResolver regions;
    private final HousingComplexRepository complexes;
    private final AnnouncementRepository announcements;
    private final Clock clock;
    private final PrivacyRetentionPolicy retentionPolicy = new PrivacyRetentionPolicy();

    @Transactional
    public MemberNotificationSettingsResponse current(long userId) {
        long revision = repository.lockUser(userId, clock.instant());
        return repository.currentSettings(userId, revision, clock.instant());
    }

    @Transactional
    public NotificationSettingsResponse change(long userId, NotificationSettingsRequest request) {
        if (!Long.toString(userId).equals(request.expectedUserId())) {
            throw new NotificationSettingsConflictException();
        }
        NotificationSettingCommand command = new NotificationSettingCommand(request.eventId(), userId,
                request.expectedSettingsRevision(), request.eventType(), request.source(), request.targetType(),
                request.targetId(), request.noticeVersion());
        long revision = repository.lockUser(userId, clock.instant());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Optional<NotificationSettingReceipt> previous = repository.findReceipt(command.eventId());
        if (previous.isPresent()) {
            previous.get().verify(command);
            return response(previous.get(), revision, now);
        }
        if (revision != command.expectedRevision()) {
            throw new NotificationSettingsConflictException();
        }
        validateConfirmation(command);
        NotificationInterestOutcome outcome = apply(command, now);
        long nextRevision = Math.incrementExact(revision);
        String noticeVersion = notices.currentVersion("NOTIFICATION_NOTICE");
        if (!repository.insertReceipt(command, outcome, noticeVersion, now, nextRevision,
                now.plus(PrivacyRetentionPolicy.NOTIFICATION_EVENT_RETENTION))) {
            throw new NotificationInterestConflictException();
        }
        repository.updateRevision(userId, revision, nextRevision, now);
        return response(new NotificationSettingReceipt(command.eventId(), userId, command.fingerprint(),
                command.targetType(), command.targetId(), outcome, now, nextRevision), nextRevision, now);
    }

    private NotificationInterestOutcome apply(NotificationSettingCommand command, Instant now) {
        CurrentTarget current = repository.currentTarget(
                command.userId(), command.targetType(), command.targetId(), now);
        if (command.eventType() == NotificationEventType.CONFIRMED) {
            if (current.active()) {
                return NotificationInterestOutcome.ALREADY_ACTIVE;
            }
            subscriptions.activateForMember(command.userId(), command.targetType(), command.targetId(), now);
            Instant expiry = repository.currentTarget(
                    command.userId(), command.targetType(), command.targetId(), now).expiresAt();
            repository.recordNotice(command, now, expiry, retentionPolicy.notificationPurgeAfter(expiry));
            return NotificationInterestOutcome.ACTIVATED;
        }
        if (!current.active()) {
            return NotificationInterestOutcome.UNCHANGED;
        }
        subscriptions.cancel(command.userId(), command.targetType(), command.targetId(), now);
        repository.retireNotice(command, retentionPolicy.notificationPurgeAfter(now));
        return NotificationInterestOutcome.CANCELLED;
    }

    private void validateConfirmation(NotificationSettingCommand command) {
        if (command.eventType() == NotificationEventType.CANCELLED) {
            return;
        }
        if (!notices.isCurrentVersion("NOTIFICATION_NOTICE", command.noticeVersion())
                || !targetExists(command.targetType(), command.targetId())) {
            throw new InvalidNotificationInterestException();
        }
    }

    private boolean targetExists(NotificationTargetType type, String id) {
        if (type == NotificationTargetType.REGION) {
            return regions.filterCodes(id).isPresent();
        }
        try {
            long numericId = Long.parseLong(id);
            if (numericId <= 0) {
                return false;
            }
            if (type == NotificationTargetType.COMPLEX) {
                return complexes.existsById(numericId);
            }
            return announcements.existsById(numericId);
        } catch (NumberFormatException exception) {
            throw new InvalidNotificationInterestException();
        }
    }

    private NotificationSettingsResponse response(NotificationSettingReceipt receipt, long revision, Instant now) {
        return new NotificationSettingsResponse(receipt.eventId(), receipt.targetType(), receipt.targetId(),
                receipt.outcome(), receipt.occurredAt(), revision,
                repository.currentTarget(receipt.userId(), receipt.targetType(), receipt.targetId(), now));
    }
}
