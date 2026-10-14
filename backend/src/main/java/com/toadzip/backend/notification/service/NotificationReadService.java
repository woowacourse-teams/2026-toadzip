package com.toadzip.backend.notification.service;

import com.toadzip.backend.notification.exception.InvalidNotificationRequestException;
import com.toadzip.backend.notification.exception.NotificationNotFoundException;
import com.toadzip.backend.notification.repository.UserNotificationRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationReadService {
    private final UserNotificationRepository repository;
    private final Clock clock;

    @Transactional
    public void markRead(long userId, long notificationId) {
        if (!repository.markRead(userId, notificationId, clock.instant())) {
            throw new NotificationNotFoundException();
        }
    }

    @Transactional
    public void markAllRead(long userId, String readThrough) {
        long bound = parseBound(readThrough);
        repository.lockUser(userId);
        if (bound == 0) {
            return;
        }
        var owner = repository.findOwner(bound);
        if (owner.isPresent() && owner.get() != userId) {
            throw new NotificationNotFoundException();
        }
        if (bound > repository.maximumId(userId, clock.instant())) {
            throw new InvalidNotificationRequestException();
        }
        if (owner.isEmpty()) {
            throw new NotificationNotFoundException();
        }
        repository.markThrough(userId, bound, clock.instant());
    }

    private long parseBound(String value) {
        try {
            if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) {
                throw new InvalidNotificationRequestException();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new InvalidNotificationRequestException();
        }
    }
}
