package com.toadzip.backend.notification.service;

import com.toadzip.backend.notification.configuration.NotificationProperties;
import com.toadzip.backend.notification.domain.NotificationAudienceSnapshot;
import com.toadzip.backend.notification.domain.ScheduleReminder;
import com.toadzip.backend.notification.repository.NotificationOutboxRepository;
import com.toadzip.backend.notification.repository.NotificationScheduleRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationScheduleService {
    private final NotificationScheduleRepository schedules;
    private final NotificationOutboxRepository outbox;
    private final NotificationProperties properties;

    @Transactional
    public int publishDue(Instant now) {
        var reminders = schedules.due(now);
        var existingKeys = outbox.existingEventKeys(reminders.stream().map(ScheduleReminder::key).toList());
        int count = 0;
        for (var reminder : reminders) {
            if (existingKeys.contains(reminder.key())) {
                continue;
            }
            if (properties.activationAt() != null && reminder.dueAt().isBefore(properties.activationAt())) {
                continue;
            }
            if (!schedules.hasSubscribers(reminder, now)) {
                continue;
            }
            var snapshot = new NotificationAudienceSnapshot("", List.of(), List.of(), reminder);
            if (outbox.appendSchedule(reminder, snapshot)) {
                count++;
            }
        }
        return count;
    }
}
