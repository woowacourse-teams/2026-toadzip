package com.toadzip.backend.notification.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.notification.configuration.NotificationProperties;
import com.toadzip.backend.notification.domain.NewAnnouncementNotificationPolicy;
import com.toadzip.backend.notification.domain.NotificationAudienceSnapshot;
import com.toadzip.backend.notification.domain.PendingNotificationEvent;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class NotificationOutboxRepository {
    private final JdbcTemplate jdbc;
    private final NotificationProperties properties;
    private final Clock clock;
    private final ObjectMapper json;
    private final NewAnnouncementNotificationPolicy policy = new NewAnnouncementNotificationPolicy();

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean appendNewAnnouncement(Announcement announcement) {
        if (!properties.publisherEnabled() || !policy.isCandidate(announcement.getCreatedAt(),
                announcement.getPostedDate(), announcement.getStatus(), properties.activationAt())) {
            return false;
        }
        return appendNewAnnouncement(announcement.getId(), announcement.getSourceAnnouncementIdentifier(), clock.instant());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean appendNewAnnouncement(long announcementId, String sourceIdentifier, Instant occurredAt) {
        return jdbc.update("""
                INSERT INTO notification_outbox_events
                    (event_key, announcement_id, source_identifier, occurred_at, status, attempts,
                     next_attempt_at, recipient_cursor)
                VALUES (?, ?, ?, ?, 'PENDING', 0, ?, 0)
                ON CONFLICT (event_key) DO NOTHING
                """, "ANNOUNCEMENT_CREATED:" + sourceIdentifier, announcementId, sourceIdentifier,
                Timestamp.from(occurredAt), Timestamp.from(occurredAt)) == 1;
    }

    public Optional<PendingNotificationEvent> claimNext(Instant now) {
        return jdbc.query("""
                SELECT * FROM notification_outbox_events WHERE status='PENDING' AND next_attempt_at<=?
                ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (row, number) -> new PendingNotificationEvent(row.getLong("id"), row.getLong("announcement_id"),
                row.getTimestamp("occurred_at").toInstant(), decodeSnapshot(row.getString("target_snapshot")),
                row.getLong("recipient_cursor"), row.getObject("recipient_upper_bound", Long.class)),
                Timestamp.from(now)).stream().findFirst();
    }

    public NotificationAudienceSnapshot decodeSnapshot(String value) {
        if (value == null) {
            return null;
        }
        return json.readValue(value, NotificationAudienceSnapshot.class);
    }

    public void saveSnapshot(long eventId, NotificationAudienceSnapshot snapshot, long upper, Instant now) {
        jdbc.update("""
                UPDATE notification_outbox_events SET target_snapshot=?,snapshot_at=?,recipient_upper_bound=? WHERE id=?
                """, json.writeValueAsString(snapshot), Timestamp.from(now), upper, eventId);
    }

    public void advance(long eventId, long cursor, boolean complete) {
        String status = "PENDING";
        if (complete) {
            status = "COMPLETED";
        }
        jdbc.update("UPDATE notification_outbox_events SET recipient_cursor=?,status=?,last_error_code=NULL WHERE id=?",
                cursor, status, eventId);
    }

    public void skip(long eventId) {
        jdbc.update("UPDATE notification_outbox_events SET status='SKIPPED',last_error_code='TARGET_UNAVAILABLE' WHERE id=?",
                eventId);
    }

    public void awaitLinks(PendingNotificationEvent event, Instant now) {
        String status = "PENDING";
        if (!now.isBefore(event.occurredAt().plus(Duration.ofHours(24)))) {
            status = "FAILED";
        }
        jdbc.update("""
                UPDATE notification_outbox_events SET status=?,next_attempt_at=?,attempts=attempts+1,
                    last_error_code='COMPLEX_LINK_PENDING' WHERE id=?
                """, status, Timestamp.from(now.plusSeconds(60)), event.id());
    }
}
