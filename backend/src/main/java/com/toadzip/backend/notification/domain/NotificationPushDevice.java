package com.toadzip.backend.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_push_devices", uniqueConstraints = @UniqueConstraint(columnNames = "fid"))
public class NotificationPushDevice {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "fid", nullable = false, length = 512)
    private String fid;

    @Column(name = "binding_version", nullable = false)
    private long bindingVersion;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "consented_at", nullable = false)
    private Instant consentedAt;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected NotificationPushDevice() {
    }
}
