package com.toadzip.backend.privacy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "privacy_notification_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PrivacyNotificationEvent {

    @Id
    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID sessionId;

    @Column(nullable = false, length = 30)
    private String eventType;

    @Column(nullable = false, length = 30)
    private String source;

    @Column(nullable = false, length = 30)
    private String targetType;

    @Column(nullable = false, length = 100)
    private String targetId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 64)
    private String requestFingerprint;

    @Column(nullable = false, length = 30)
    private String outcome;

    @Column(nullable = false)
    private Instant purgeAfter;
}
