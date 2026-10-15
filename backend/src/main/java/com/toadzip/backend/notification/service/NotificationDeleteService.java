package com.toadzip.backend.notification.service;

import com.toadzip.backend.notification.dto.NotificationDeleteRequest;
import com.toadzip.backend.notification.exception.InvalidNotificationRequestException;
import com.toadzip.backend.notification.exception.NotificationNotFoundException;
import com.toadzip.backend.notification.repository.UserNotificationRepository;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationDeleteService {
    private final UserNotificationRepository repository;
    private final Clock clock;

    @Transactional
    public void delete(long userId, NotificationDeleteRequest request) {
        if ((request.ids() == null) == (request.deleteThrough() == null)) {
            throw new InvalidNotificationRequestException();
        }
        repository.lockUser(userId);
        if (request.deleteThrough() != null) {
            long bound = parseId(request.deleteThrough());
            verifyOwner(userId, bound);
            var excluded = exclusions(request, bound);
            if (repository.countOwned(userId, excluded) != excluded.size()) {
                throw new NotificationNotFoundException();
            }
            repository.deleteThrough(userId, bound, excluded, clock.instant());
            return;
        }
        if (request.excludedIds() != null || request.ids().isEmpty() || request.ids().size() > 1000) {
            throw new InvalidNotificationRequestException();
        }
        var ids = request.ids().stream().map(this::parseId).distinct().toList();
        if (repository.countOwned(userId, ids) != ids.size()) {
            throw new NotificationNotFoundException();
        }
        repository.deleteSelected(userId, ids, clock.instant());
    }

    private List<Long> exclusions(NotificationDeleteRequest request, long bound) {
        if (request.excludedIds() == null) {
            return List.of();
        }
        if (request.excludedIds().size() > 1000) {
            throw new InvalidNotificationRequestException();
        }
        var ids = request.excludedIds().stream().map(this::parseId).distinct().toList();
        if (ids.stream().anyMatch(id -> id > bound)) {
            throw new InvalidNotificationRequestException();
        }
        return ids;
    }

    private void verifyOwner(long userId, long id) {
        if (repository.findOwner(id).filter(owner -> owner == userId).isEmpty()) {
            throw new NotificationNotFoundException();
        }
    }

    private long parseId(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
                throw new InvalidNotificationRequestException();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new InvalidNotificationRequestException();
        }
    }
}
