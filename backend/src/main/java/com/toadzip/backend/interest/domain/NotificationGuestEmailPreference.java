package com.toadzip.backend.interest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_guest_email_preferences")
public class NotificationGuestEmailPreference {

    @Id
    private UUID clientId;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false)
    private Instant updatedAt;

    protected NotificationGuestEmailPreference() {
    }
}
