package com.toadzip.backend.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Check;

@Entity
@Table(name = "notification_deliveries", uniqueConstraints = @UniqueConstraint(name = "uk_notification_delivery_device",
        columnNames = {"notification_id", "device_id", "device_binding_version"}))
@Check(constraints = "status IN ('PENDING','SENDING','RETRY','SENT','SKIPPED','FAILED')")
public class NotificationDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "device_binding_version", nullable = false)
    private long deviceBindingVersion;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_token", nullable = true)
    private UUID leaseToken;

    @Column(name = "lease_until", nullable = true)
    private Instant leaseUntil;

    @Column(name = "provider_message_id", nullable = true, length = 512)
    private String providerMessageId;

    @Column(name = "last_error_code", nullable = true, length = 100)
    private String lastErrorCode;

    @Column(name = "finished_at", nullable = true)
    private Instant finishedAt;

    protected NotificationDelivery() {
    }
}
