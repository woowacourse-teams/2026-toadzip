package com.toadzip.backend.interest.domain;

import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.interest.exception.InvalidNotificationInterestException;
import com.toadzip.backend.interest.exception.NotificationInterestConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

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

    @Column(nullable = false)
    private Instant createdAt;

    @Column(length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'UNKNOWN'")
    private NotificationInterestOutcome outcome = NotificationInterestOutcome.UNKNOWN;

    private NotificationInterestEvent(UUID eventId, UUID sessionId, NotificationEventType eventType,
            NotificationEventSource source, NotificationTargetType targetType, String targetId,
            Instant createdAt) {
        this.eventId = eventId;
        this.sessionId = sessionId;
        this.eventType = eventType;
        this.source = source;
        this.targetType = targetType;
        this.targetId = targetId;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
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
        return new NotificationInterestEvent(eventId, sessionId, eventType, source, targetType, targetId,
                createdAt);
    }

    public static NotificationInterestEvent forRequest(UUID eventId, UUID sessionId, NotificationEventType eventType,
            NotificationEventSource source, NotificationTargetType targetType, String targetId, String email,
            Instant createdAt, Long userId, UUID clientId) {
        NotificationInterestEvent event = create(eventId, sessionId, eventType, source, targetType, targetId,
                email, createdAt);
        String actor = "session:" + sessionId;
        if (clientId != null) {
            actor = "client:" + clientId;
        }
        if (userId != null) {
            actor = "user:" + userId;
        }
        event.requestFingerprint = fingerprint(String.join("|", eventId.toString(), sessionId.toString(),
                eventType.name(), source.name(), targetType.name(), targetId, actor, String.valueOf(email)));
        return event;
    }

    public static NotificationInterestEvent restore(UUID eventId, UUID sessionId, NotificationEventType eventType,
            NotificationEventSource source, NotificationTargetType targetType, String targetId, Instant createdAt,
            String requestFingerprint, NotificationInterestOutcome outcome) {
        NotificationInterestEvent event = new NotificationInterestEvent(eventId, sessionId, eventType, source,
                targetType, targetId, createdAt);
        event.requestFingerprint = requestFingerprint;
        event.outcome = outcome;
        return event;
    }

    public void verifySameRequest(NotificationInterestEvent attempted) {
        if (requestFingerprint != null) {
            if (!requestFingerprint.equals(attempted.requestFingerprint)) {
                throw new NotificationInterestConflictException();
            }
            return;
        }
        // Historical rows have no actor identity; they can only return UNKNOWN, never replay a write.
        if (!sessionId.equals(attempted.sessionId) || eventType != attempted.eventType || source != attempted.source
                || targetType != attempted.targetType || !targetId.equals(attempted.targetId)) {
            throw new NotificationInterestConflictException();
        }
    }

    public void complete(NotificationInterestOutcome outcome) {
        if (outcome == null || outcome == NotificationInterestOutcome.UNKNOWN) {
            throw new IllegalArgumentException("알림 요청의 실제 처리 결과가 필요합니다.");
        }
        this.outcome = outcome;
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("알림 요청 식별자를 생성할 수 없습니다.", exception);
        }
    }
}
