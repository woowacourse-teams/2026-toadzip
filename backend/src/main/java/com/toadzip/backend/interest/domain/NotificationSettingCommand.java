package com.toadzip.backend.interest.domain;

import com.toadzip.backend.interest.exception.InvalidNotificationInterestException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public record NotificationSettingCommand(UUID eventId, long userId, long expectedRevision,
        NotificationEventType eventType, NotificationEventSource source, NotificationTargetType targetType,
        String targetId, String noticeVersion) {

    public NotificationSettingCommand {
        if (eventId == null || userId <= 0 || expectedRevision < 0 || eventType == null || source == null
                || targetType == null || targetId == null || !targetId.matches("[0-9]{1,19}")) {
            throw new InvalidNotificationInterestException();
        }
        if (eventType != NotificationEventType.CONFIRMED && eventType != NotificationEventType.CANCELLED) {
            throw new InvalidNotificationInterestException();
        }
        if (source != NotificationEventSource.SETTING && !source.supports(targetType)) {
            throw new InvalidNotificationInterestException();
        }
    }

    public String fingerprint() {
        String content = String.join("|", eventId.toString(), Long.toString(userId),
                Long.toString(expectedRevision), eventType.name(), source.name(), targetType.name(), targetId,
                String.valueOf(noticeVersion));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("알림 요청 식별자를 생성할 수 없습니다.", exception);
        }
    }
}
