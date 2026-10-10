package com.toadzip.backend.privacy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "privacy_notification_states")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PrivacyNotificationState {

    @Id
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private long revision;

    @Column(nullable = false)
    private Instant updatedAt;
}
