package com.toadzip.backend.notification.repository;

import com.toadzip.backend.notification.domain.ClaimedNotificationDelivery;
import com.toadzip.backend.notification.domain.NotificationAudienceSnapshot;
import com.toadzip.backend.notification.domain.NotificationRetryPolicy;
import com.toadzip.backend.notification.domain.NotificationSendResult;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationDeliveryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final NotificationPushDeviceRepository devices;
    private final NotificationRetryPolicy retry = new NotificationRetryPolicy();

    public int enqueueForCurrentDevices(long notificationId, Instant now) {
        return jdbc.update("""
                INSERT INTO notification_deliveries(notification_id,device_id,device_binding_version,status,
                    attempts,next_attempt_at)
                SELECT n.id,d.id,d.binding_version,'PENDING',0,? FROM user_notifications n
                    JOIN notification_push_devices d ON d.user_id=n.user_id AND d.enabled
                WHERE n.id=?
                ON CONFLICT(notification_id,device_id,device_binding_version) DO NOTHING
                """, Timestamp.from(now), notificationId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ClaimedNotificationDelivery> claimNext(Instant now, Duration lease) {
        var candidates = jdbc.query("""
                SELECT d.*,n.user_id,n.announcement_id,e.occurred_at,e.target_snapshot,p.fid
                FROM notification_deliveries d JOIN user_notifications n ON n.id=d.notification_id
                    JOIN notification_outbox_events e ON e.id=n.event_id
                    JOIN notification_push_devices p ON p.id=d.device_id
                WHERE (d.status IN ('PENDING','RETRY') AND d.next_attempt_at<=?)
                    OR (d.status='SENDING' AND d.lease_until<=?)
                ORDER BY COALESCE(d.lease_until,d.next_attempt_at),d.id LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                """, (row, number) -> new ClaimedNotificationDelivery(row.getLong("id"), UUID.randomUUID(),
                row.getLong("notification_id"), row.getObject("device_id", UUID.class),
                row.getLong("device_binding_version"), row.getString("fid"), row.getLong("user_id"),
                row.getLong("announcement_id"), row.getInt("attempts") + 1,
                row.getTimestamp("occurred_at").toInstant(),
                json.readValue(row.getString("target_snapshot"), NotificationAudienceSnapshot.class)),
                Timestamp.from(now), Timestamp.from(now));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        var claimed = candidates.getFirst();
        jdbc.update("""
                UPDATE notification_deliveries SET status='SENDING',attempts=attempts+1,lease_token=?,lease_until=?,
                    finished_at=NULL WHERE id=?
                """, claimed.leaseToken(), Timestamp.from(now.plus(lease)), claimed.id());
        return Optional.of(claimed);
    }

    public boolean hasCurrentBinding(ClaimedNotificationDelivery delivery, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM notification_push_devices d JOIN user_notifications n ON n.user_id=d.user_id
                    WHERE d.id=? AND d.enabled AND d.binding_version=? AND d.user_id=? AND n.id=? AND n.expires_at>?)
                """, Boolean.class, delivery.deviceId(), delivery.bindingVersion(), delivery.userId(),
                delivery.notificationId(), Timestamp.from(now)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordResult(ClaimedNotificationDelivery delivery, NotificationSendResult result, Instant now) {
        String status = terminalStatus(result);
        Instant next = now;
        Timestamp finished = Timestamp.from(now);
        if (result.status() == NotificationSendResult.Status.RETRYABLE
                && !retry.isExhausted(delivery.attempts(), delivery.occurredAt(), now)) {
            Instant candidate = retry.nextAttemptAt(now, delivery.attempts(), result.retryAfter(),
                    Duration.ofSeconds(ThreadLocalRandom.current().nextLong(16)));
            if (candidate.isBefore(delivery.occurredAt().plus(Duration.ofHours(24)))) {
                next = candidate;
                status = "RETRY";
                finished = null;
            }
        }
        int updated = jdbc.update("""
                UPDATE notification_deliveries SET status=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,
                    provider_message_id=?,last_error_code=?,finished_at=?
                WHERE id=? AND status='SENDING' AND lease_token=?
                """, status, Timestamp.from(next), result.providerMessageId(), result.errorCode(), finished,
                delivery.id(), delivery.leaseToken());
        if (updated == 1 && result.status() == NotificationSendResult.Status.INVALID_REGISTRATION) {
            devices.disable(delivery.userId(), delivery.deviceId(), delivery.bindingVersion(), now);
        }
        return updated == 1;
    }

    private String terminalStatus(NotificationSendResult result) {
        return switch (result.status()) {
            case ACCEPTED -> "SENT";
            case SKIPPED, INVALID_REGISTRATION -> "SKIPPED";
            case RETRYABLE, PERMANENT_FAILURE -> "FAILED";
        };
    }
}
