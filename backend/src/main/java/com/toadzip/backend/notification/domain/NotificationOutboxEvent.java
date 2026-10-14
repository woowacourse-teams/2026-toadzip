package com.toadzip.backend.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "notification_outbox_events", uniqueConstraints = @UniqueConstraint(columnNames = "event_key"))
@Check(constraints = "status IN ('PENDING','COMPLETED','SKIPPED','FAILED')")
public class NotificationOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", nullable = false, length = 512)
    private String eventKey;

    @Column(name = "announcement_id", nullable = false)
    private Long announcementId;

    @Column(name = "source_identifier", nullable = false, length = 255)
    private String sourceIdentifier;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "target_snapshot", columnDefinition = "text")
    private String targetSnapshot;

    @Column(name = "snapshot_at", nullable = true)
    private Instant snapshotAt;

    @Column(name = "recipient_cursor", nullable = false)
    private long recipientCursor;

    @Column(name = "recipient_upper_bound", nullable = true)
    private Long recipientUpperBound;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error_code", nullable = true, length = 100)
    private String lastErrorCode;

    protected NotificationOutboxEvent() {
    }
}
