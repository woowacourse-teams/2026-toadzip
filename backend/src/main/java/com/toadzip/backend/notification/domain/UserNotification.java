package com.toadzip.backend.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "user_notifications", uniqueConstraints = @UniqueConstraint(name = "uk_user_notification_event",
        columnNames = {"user_id", "event_id"}))
public class UserNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "announcement_id", nullable = false)
    private Long announcementId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "read_at", nullable = true)
    private Instant readAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected UserNotification() {
    }
}
