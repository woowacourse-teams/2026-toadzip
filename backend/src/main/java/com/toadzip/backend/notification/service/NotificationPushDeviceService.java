package com.toadzip.backend.notification.service;

import com.toadzip.backend.notification.domain.NotificationDeviceState;
import com.toadzip.backend.notification.dto.NotificationDeviceBinding;
import com.toadzip.backend.notification.dto.NotificationPushDeviceRequest;
import com.toadzip.backend.notification.dto.NotificationPushDeviceResponse;
import com.toadzip.backend.notification.dto.NotificationPushDisableRequest;
import com.toadzip.backend.notification.exception.InvalidNotificationRequestException;
import com.toadzip.backend.notification.exception.NotificationDeviceConflictException;
import com.toadzip.backend.notification.repository.NotificationPushDeviceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationPushDeviceService {
    private final NotificationPushDeviceRepository repository;
    private final Clock clock;

    @Transactional
    public NotificationPushDeviceResponse register(long userId, UUID installation, NotificationPushDeviceRequest request) {
        verifyUser(userId, request.expectedUserId());
        if (request.fid() == null || request.fid().isBlank() || request.fid().length() > 512
                || request.fid().codePoints().anyMatch(Character::isWhitespace)) {
            throw new InvalidNotificationRequestException();
        }
        repository.lockRegistration(installation, request.fid());
        var existing = repository.find(installation);
        Long expected = parseVersion(request.expectedBindingVersion());
        if (existing.isPresent() && !Long.valueOf(existing.get().bindingVersion()).equals(expected)) {
            throw new NotificationDeviceConflictException();
        }
        if (existing.isEmpty() && expected != null || repository.hasOtherInstallation(installation, request.fid())) {
            throw new NotificationDeviceConflictException();
        }
        Instant consentedAt = clock.instant();
        long version = 1;
        if (existing.isPresent()) {
            var previous = existing.get();
            version = Math.incrementExact(previous.bindingVersion());
            if (previous.retainsBinding(userId, request.fid())) {
                version = previous.bindingVersion();
                consentedAt = previous.consentedAt();
            }
        }
        var device = new NotificationDeviceState(installation, userId, request.fid(), version, true, consentedAt);
        repository.save(device, clock.instant());
        return response(device, userId);
    }

    @Transactional(readOnly = true)
    public NotificationPushDeviceResponse current(long userId, UUID installation) {
        return repository.find(installation).map(device -> response(device, userId))
                .orElseGet(() -> new NotificationPushDeviceResponse(null, false, null));
    }

    @Transactional
    public void disable(long userId, UUID installation, NotificationPushDisableRequest request) {
        verifyUser(userId, request.expectedUserId());
        Long version = parseVersion(request.bindingVersion());
        if (version == null) {
            throw new InvalidNotificationRequestException();
        }
        if (installation == null) {
            throw new NotificationDeviceConflictException();
        }
        if (repository.disable(userId, installation, version, clock.instant())) {
            return;
        }
        var current = repository.find(installation);
        if (current.isPresent()) {
            var device = current.get();
            if (device.userId() == userId && !device.enabled()
                    && (device.bindingVersion() == version || device.bindingVersion() - 1 == version)) {
                return;
            }
        }
        throw new NotificationDeviceConflictException();
    }

    @Transactional
    public void disableSessionBinding(long userId, NotificationDeviceBinding binding) {
        if (binding != null && binding.userId() == userId) {
            repository.disable(userId, binding.deviceId(), binding.version(), clock.instant());
        }
    }

    private NotificationPushDeviceResponse response(NotificationDeviceState device, long userId) {
        return new NotificationPushDeviceResponse(device.id().toString(), device.enabled() && device.userId() == userId,
                Long.toString(device.bindingVersion()));
    }

    private void verifyUser(long userId, String expected) {
        if (!Long.toString(userId).equals(expected)) {
            throw new NotificationDeviceConflictException();
        }
    }

    private Long parseVersion(String value) {
        if (value == null) {
            return null;
        }
        try {
            long version = Long.parseLong(value);
            if (version < 1) {
                throw new InvalidNotificationRequestException();
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new InvalidNotificationRequestException();
        }
    }
}
