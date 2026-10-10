package com.toadzip.backend.privacy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "privacy_notification_notices")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PrivacyNotificationNotice {

    @EmbeddedId
    private Target id;

    @Column(nullable = false, length = 100)
    private String noticeVersion;

    @Column(nullable = false)
    private Instant requestedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant purgeAfter;

    @Embeddable
    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Target implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(nullable = false)
        private Long userId;

        @Column(nullable = false, length = 20)
        private String targetType;

        @Column(nullable = false, length = 100)
        private String targetId;
    }
}
