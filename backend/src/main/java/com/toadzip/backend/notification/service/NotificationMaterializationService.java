package com.toadzip.backend.notification.service;

import com.toadzip.backend.notification.domain.NotificationAudienceSnapshot;
import com.toadzip.backend.notification.domain.NotificationRecipient;
import com.toadzip.backend.notification.domain.PendingNotificationEvent;
import com.toadzip.backend.notification.repository.NotificationAudienceRepository;
import com.toadzip.backend.notification.repository.NotificationDeliveryRepository;
import com.toadzip.backend.notification.repository.NotificationOutboxRepository;
import com.toadzip.backend.notification.repository.NotificationScheduleRepository;
import com.toadzip.backend.notification.repository.UserNotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationMaterializationService {
    private final NotificationOutboxRepository outbox;
    private final NotificationAudienceRepository audience;
    private final UserNotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationScheduleRepository schedules;

    @Transactional
    public int materializeNextPage(Instant now, int pageSize) {
        if (pageSize < 1 || pageSize > 200) {
            throw new IllegalArgumentException("알림 생성 페이지 크기는 1~200입니다.");
        }
        Optional<PendingNotificationEvent> claimed = outbox.claimNext(now);
        if (claimed.isEmpty()) {
            return 0;
        }
        return materialize(claimed.get(), now, pageSize);
    }

    private int materialize(PendingNotificationEvent event, Instant now, int pageSize) {
        if (event.snapshot() != null && event.snapshot().schedule() != null
                && !schedules.isCurrent(event.snapshot().schedule(), now)) {
            outbox.skip(event.id());
            return 0;
        }
        Optional<String> title = audience.visibleTitle(event.announcementId(), event.snapshot());
        if (title.isEmpty()) {
            outbox.skip(event.id());
            return 0;
        }
        NotificationAudienceSnapshot snapshot = event.snapshot();
        Long upper = event.recipientUpperBound();
        if (snapshot == null || upper == null) {
            if (snapshot != null && snapshot.schedule() != null) {
                snapshot = new NotificationAudienceSnapshot(title.get(), List.of(), List.of(), snapshot.schedule());
            } else {
                snapshot = audience.snapshot(event.announcementId(), title.get());
            }
            if (snapshot.schedule() == null && snapshot.complexes().isEmpty()) {
                outbox.awaitLinks(event, now);
                return 0;
            }
            upper = audience.upperUserId();
            outbox.saveSnapshot(event.id(), snapshot, upper, now);
        }
        List<NotificationRecipient> recipients = audience.findRecipients(snapshot, event.occurredAt(),
                event.recipientCursor(), upper, pageSize, now);
        List<Long> created = notifications.insertAllIfAbsent(event.id(), event.announcementId(), snapshot.title(),
                recipients, now);
        deliveries.enqueueForCurrentDevices(created, now);
        long cursor = event.recipientCursor();
        if (!recipients.isEmpty()) {
            cursor = recipients.getLast().userId();
        }
        outbox.advance(event.id(), cursor, recipients.size() < pageSize);
        return recipients.size();
    }
}
