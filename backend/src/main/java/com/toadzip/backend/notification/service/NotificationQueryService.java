package com.toadzip.backend.notification.service;

import com.toadzip.backend.notification.domain.NotificationCursor;
import com.toadzip.backend.notification.dto.NotificationPageResponse;
import com.toadzip.backend.notification.dto.NotificationUnreadCountResponse;
import com.toadzip.backend.notification.exception.InvalidNotificationRequestException;
import com.toadzip.backend.notification.repository.UserNotificationRepository;
import java.time.Clock;
import java.time.DateTimeException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationQueryService {
    private final UserNotificationRepository repository;
    private final Clock clock;

    public NotificationPageResponse findForUser(long userId, String cursor, int size) {
        if (size < 1 || size > 50) {
            throw new InvalidNotificationRequestException();
        }
        try {
            return repository.findPage(userId, NotificationCursor.decode(cursor), size, clock.instant());
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new InvalidNotificationRequestException();
        }
    }

    public NotificationUnreadCountResponse countUnread(long userId) {
        return new NotificationUnreadCountResponse(repository.countUnread(userId, clock.instant()));
    }
}
