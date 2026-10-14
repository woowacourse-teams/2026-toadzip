package com.toadzip.backend.notification.repository;

import com.google.api.core.ApiFuture;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.WebpushConfig;
import com.google.firebase.messaging.WebpushFcmOptions;
import com.google.firebase.messaging.WebpushNotification;
import com.toadzip.backend.notification.domain.ClaimedNotificationDelivery;
import com.toadzip.backend.notification.domain.NotificationSendResult;
import com.toadzip.backend.notification.domain.NotificationSendResult.Status;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class FirebaseNotificationPushSender implements NotificationPushSender {
    private final FirebaseMessaging messaging;
    private final String notificationLink;
    private final Clock clock;
    private final Duration deadline;

    public FirebaseNotificationPushSender(FirebaseMessaging messaging, String origin, Clock clock, Duration deadline) {
        this.messaging = messaging;
        this.notificationLink = notificationLink(origin);
        this.clock = clock;
        if (deadline.isNegative() || deadline.isZero() || deadline.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("FCM 호출 제한시간은 30초 이하여야 합니다.");
        }
        this.deadline = deadline;
    }

    @Override
    public NotificationSendResult send(ClaimedNotificationDelivery delivery) {
        long ttl = Duration.between(clock.instant(), delivery.occurredAt().plus(Duration.ofHours(24))).toSeconds();
        if (ttl <= 0) {
            return new NotificationSendResult(Status.PERMANENT_FAILURE, null, "RETRY_WINDOW_EXHAUSTED", null);
        }
        ApiFuture<String> pending = messaging.sendAsync(message(delivery, Math.min(ttl, 86400)));
        try {
            return new NotificationSendResult(Status.ACCEPTED,
                    pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS), null, null);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            return new NotificationSendResult(Status.RETRYABLE, null, "TIMEOUT", null);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return new NotificationSendResult(Status.RETRYABLE, null, "INTERRUPTED", null);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof FirebaseMessagingException firebase) {
                return failure(firebase);
            }
            return new NotificationSendResult(Status.RETRYABLE, null, "TRANSPORT_ERROR", null);
        }
    }

    private Message message(ClaimedNotificationDelivery delivery, long ttl) {
        return Message.builder().setFid(delivery.fid())
                .setNotification(Notification.builder().setTitle("복덕방")
                        .setBody("새 공고 알림이 있어요. 알림 보관함에서 확인해 주세요.").build())
                .putData("notificationId", Long.toString(delivery.notificationId()))
                .setWebpushConfig(WebpushConfig.builder().putHeader("TTL", Long.toString(ttl))
                        .setNotification(WebpushNotification.builder()
                                .setTag("notification-" + delivery.notificationId()).build())
                        .setFcmOptions(WebpushFcmOptions.withLink(notificationLink)).build()).build();
    }

    private NotificationSendResult failure(FirebaseMessagingException exception) {
        if (exception.getMessagingErrorCode() == null) {
            String code = "TRANSPORT_ERROR";
            if (exception.getErrorCode() != null) {
                code = exception.getErrorCode().name();
            }
            if (List.of("UNAUTHENTICATED", "PERMISSION_DENIED", "INVALID_ARGUMENT", "FAILED_PRECONDITION")
                    .contains(code)) {
                return new NotificationSendResult(Status.PERMANENT_FAILURE, null, code, null);
            }
            return new NotificationSendResult(Status.RETRYABLE, null, code, retryAfter(exception));
        }
        Status status = switch (exception.getMessagingErrorCode()) {
            case UNREGISTERED -> Status.INVALID_REGISTRATION;
            case INTERNAL, QUOTA_EXCEEDED, UNAVAILABLE -> Status.RETRYABLE;
            case INVALID_ARGUMENT, SENDER_ID_MISMATCH, THIRD_PARTY_AUTH_ERROR -> Status.PERMANENT_FAILURE;
        };
        return new NotificationSendResult(status, null, exception.getMessagingErrorCode().name(), retryAfter(exception));
    }

    private Instant retryAfter(FirebaseMessagingException exception) {
        if (exception.getHttpResponse() == null) {
            return null;
        }
        Object value = exception.getHttpResponse().getHeaders().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("Retry-After")).map(java.util.Map.Entry::getValue)
                .findFirst().orElse(null);
        if (value instanceof List<?> values && !values.isEmpty()) {
            value = values.getFirst();
        }
        if (value == null) {
            return null;
        }
        String header = value.toString().trim();
        try {
            return clock.instant().plusSeconds(Math.min(86400, Math.max(0, Long.parseLong(header))));
        } catch (NumberFormatException exceptionIgnored) {
            try {
                return ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (java.time.DateTimeException invalidDate) {
                return null;
            }
        }
    }

    private static String notificationLink(String origin) {
        if (origin == null || origin.isBlank()) {
            throw new IllegalArgumentException("FCM 클릭 링크의 HTTPS origin이 필요합니다.");
        }
        URI uri = URI.create(origin);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("FCM 클릭 링크는 경로 없는 HTTPS origin이어야 합니다.");
        }
        return uri.resolve("/notifications").toString();
    }
}
