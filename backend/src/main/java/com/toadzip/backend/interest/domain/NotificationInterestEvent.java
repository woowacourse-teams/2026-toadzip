package com.toadzip.backend.interest.domain;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.interest.exception.InvalidNotificationInterestException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "notification_interest_events")
@NoArgsConstructor(access = PROTECTED)
public class NotificationInterestEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID eventId;

    @Column(nullable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationEventSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationTargetType targetType;

    @Column(nullable = false, length = 19)
    private String targetId;

    @Column(length = 254)
    private String email;

    @Column(nullable = false)
    private Instant createdAt;

    private NotificationInterestEvent(UUID eventId, UUID sessionId, NotificationEventType eventType,
            NotificationEventSource source, NotificationTargetType targetType, String targetId, String email,
            Instant createdAt) {
        this.eventId = eventId;
        this.sessionId = sessionId;
        this.eventType = eventType;
        this.source = source;
        this.targetType = targetType;
        this.targetId = targetId;
        this.email = email;
        this.createdAt = createdAt;
    }

    public static NotificationInterestEvent create(UUID eventId, UUID sessionId, NotificationEventType eventType,
            NotificationEventSource source, NotificationTargetType targetType, String targetId, Instant createdAt) {
        return create(eventId, sessionId, eventType, source, targetType, targetId, null, createdAt);
    }

    public static NotificationInterestEvent create(UUID eventId, UUID sessionId, NotificationEventType eventType,
            NotificationEventSource source, NotificationTargetType targetType, String targetId, String email,
            Instant createdAt) {
        if (eventId == null || sessionId == null || eventType == null || source == null || targetType == null
                || targetId == null || !targetId.matches("[0-9]{1,19}") || createdAt == null) {
            throw new InvalidNotificationInterestException();
        }
        if (!source.supports(targetType)) {
            throw new InvalidNotificationInterestException();
        }
        if (email != null && (email.length() > 254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) {
            throw new InvalidNotificationInterestException();
        }
        if (eventType != NotificationEventType.CONFIRMED && email != null) {
            throw new InvalidNotificationInterestException();
        }
        return new NotificationInterestEvent(eventId, sessionId, eventType, source, targetType, targetId, email,
                createdAt);
    }
}
