package com.toadzip.backend.notification.service;

import com.toadzip.backend.notification.domain.ClaimedNotificationDelivery;
import com.toadzip.backend.notification.domain.NotificationRetryPolicy;
import com.toadzip.backend.notification.domain.NotificationSendResult;
import com.toadzip.backend.notification.domain.NotificationSendResult.Status;
import com.toadzip.backend.notification.repository.NotificationAudienceRepository;
import com.toadzip.backend.notification.repository.NotificationDeliveryRepository;
import com.toadzip.backend.notification.repository.NotificationPushSender;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
public class NotificationDeliveryService {
    private final NotificationDeliveryRepository deliveries;
    private final NotificationAudienceRepository audience;
    private final NotificationPushSender sender;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final NotificationRetryPolicy retry = new NotificationRetryPolicy();

    public NotificationDeliveryService(NotificationDeliveryRepository deliveries,
            NotificationAudienceRepository audience, NotificationPushSender sender,
            PlatformTransactionManager manager, Clock clock) {
        this.deliveries = deliveries;
        this.audience = audience;
        this.sender = sender;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
    }

    public int deliverAvailable(int limit) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("FCM 발송을 DB 트랜잭션 내부에서 실행할 수 없습니다.");
        }
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("발송 작업 수는 1~20입니다.");
        }
        int count = 0;
        while (count < limit) {
            var claimed = transactions.execute(status -> deliveries.claimNext(clock.instant(), Duration.ofSeconds(120)));
            if (claimed.isEmpty()) {
                return count;
            }
            deliver(claimed.get());
            count++;
        }
        return count;
    }

    private void deliver(ClaimedNotificationDelivery delivery) {
        var valid = transactions.execute(status -> {
            var members = new java.util.ArrayList<ClaimedNotificationDelivery>();
            for (var member : delivery.members()) {
                var skipped = validate(member);
                if (skipped != null) {
                    deliveries.recordResult(member, skipped, clock.instant());
                    continue;
                }
                members.add(member);
            }
            return members;
        });
        if (valid.isEmpty()) {
            return;
        }
        NotificationSendResult result = sender.send(valid.getFirst().withMembers(valid));
        try {
            transactions.executeWithoutResult(status -> deliveries.recordResults(valid, result, clock.instant()));
        } catch (RuntimeException exception) {
            log.error("notification_result_not_recorded deliveryId={} providerStatus={}", delivery.id(), result.status());
            throw exception;
        }
    }

    private NotificationSendResult validate(ClaimedNotificationDelivery delivery) {
        var now = clock.instant();
        if (retry.isExhausted(delivery.attempts() - 1, delivery.occurredAt(), now)) {
            return new NotificationSendResult(Status.PERMANENT_FAILURE, null, "RETRY_WINDOW_EXHAUSTED", null);
        }
        if (!deliveries.hasCurrentBinding(delivery, now) || audience.visibleTitle(delivery.announcementId(), delivery.snapshot()).isEmpty()
                || delivery.snapshot() == null
                || !audience.isSubscribed(delivery.userId(), delivery.snapshot(), delivery.content(), delivery.occurredAt(), now)) {
            return new NotificationSendResult(Status.SKIPPED, null, "AUDIENCE_OR_DEVICE_CHANGED", null);
        }
        return null;
    }
}
