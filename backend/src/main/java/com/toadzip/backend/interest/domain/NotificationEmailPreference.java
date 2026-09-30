package com.toadzip.backend.interest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "notification_email_preferences")
public class NotificationEmailPreference {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false)
    private Instant updatedAt;

    protected NotificationEmailPreference() {
    }
}
