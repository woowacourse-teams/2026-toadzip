package com.toadzip.backend.notification.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

public record NotificationCursor(Instant createdAt, long id) {
    public NotificationCursor {
        if (createdAt == null || createdAt.isBefore(Instant.EPOCH)
                || createdAt.isAfter(Instant.parse("9999-12-31T23:59:59Z")) || id <= 0) {
            throw new IllegalArgumentException("잘못된 알림 커서입니다.");
        }
    }

    public static NotificationCursor decode(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("잘못된 알림 커서입니다.");
        }
        String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        String[] parts = decoded.split("[|]", -1);
        if (parts.length != 2 || !parts[1].matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("잘못된 알림 커서입니다.");
        }
        return new NotificationCursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }
}
