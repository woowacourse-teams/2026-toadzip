package com.toadzip.backend.interest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_guest_cancellation_requests")
public class NotificationGuestCancellationRequest {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(nullable = false)
    private Instant requestedAt;

    @Column(length = 64)
    private String codeHash;

    private Instant codeExpiresAt;

    private Instant codeSentAt;

    @Column(length = 254)
    private String codeSentBy;

    @Column(nullable = false)
    private int failedAttempts;

    protected NotificationGuestCancellationRequest() {
    }
}
