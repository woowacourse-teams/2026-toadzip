package com.toadzip.backend.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.core.ApiFutures;
import com.google.api.core.SettableApiFuture;
import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.IncomingHttpResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.toadzip.backend.notification.domain.ClaimedNotificationDelivery;
import com.toadzip.backend.notification.domain.NotificationSendResult.Status;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FirebaseNotificationPushSenderTest {
    private final Instant now = Instant.parse("2026-10-13T00:00:00Z");
    private final FirebaseMessaging messaging = mock(FirebaseMessaging.class);
    private final ClaimedNotificationDelivery delivery = new ClaimedNotificationDelivery(1, UUID.randomUUID(), 101,
            UUID.randomUUID(), 1, "test-installation", 10, 20, 1, now.minusSeconds(3600), null);

    @Test
    void FID와_일반문구_보관함링크_남은TTL_중복표시태그를_보낸다() throws Exception {
        when(messaging.sendAsync(any(Message.class))).thenReturn(ApiFutures.immediateFuture("projects/test/messages/1"));
        var result = sender(Duration.ofSeconds(30)).send(delivery);
        assertThat(result.status()).isEqualTo(Status.ACCEPTED);
        var argument = ArgumentCaptor.forClass(Message.class);
        verify(messaging).sendAsync(argument.capture());
        String payload = GsonFactory.getDefaultInstance().toString(argument.getValue());
        assertThat(payload).contains("\"fid\":\"test-installation\"", "https://example.com/notifications",
                "\"tag\":\"notification-101\"", "\"TTL\":\"82800\"")
                .doesNotContain("userId", "announcementId", "관심 단지");
    }

    @Test
    void 일반_INVALID_ARGUMENT는_기기등록_해제로_취급하지_않는다() {
        failWith(MessagingErrorCode.INVALID_ARGUMENT, null);
        assertThat(sender(Duration.ofSeconds(30)).send(delivery).status()).isEqualTo(Status.PERMANENT_FAILURE);
        failWith(MessagingErrorCode.UNREGISTERED, null);
        assertThat(sender(Duration.ofSeconds(30)).send(delivery).status()).isEqualTo(Status.INVALID_REGISTRATION);
    }

    @Test
    void 할당량_오류의_RetryAfter를_전달한다() {
        var response = mock(IncomingHttpResponse.class);
        when(response.getHeaders()).thenReturn(Map.of("retry-after", List.of("300")));
        failWith(MessagingErrorCode.QUOTA_EXCEEDED, response);
        var result = sender(Duration.ofSeconds(30)).send(delivery);
        assertThat(result.status()).isEqualTo(Status.RETRYABLE);
        assertThat(result.retryAfter()).isEqualTo(Instant.parse("2026-10-13T00:05:00Z"));
    }

    @Test
    void 호출_제한시간이_지나면_Future를_취소하고_재시도로_남긴다() {
        var pending = SettableApiFuture.<String>create();
        when(messaging.sendAsync(any(Message.class))).thenReturn(pending);
        long started = System.nanoTime();
        var result = sender(Duration.ofMillis(30)).send(delivery);
        assertThat(result.errorCode()).isEqualTo("TIMEOUT");
        assertThat(pending.isCancelled()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void 과도한_RetryAfter는_재시도_유효기간_밖으로_제한한다() {
        var response = mock(IncomingHttpResponse.class);
        when(response.getHeaders()).thenReturn(Map.of("Retry-After", List.of(Long.toString(Long.MAX_VALUE))));
        failWith(MessagingErrorCode.QUOTA_EXCEEDED, response);
        var result = sender(Duration.ofSeconds(30)).send(delivery);
        assertThat(result.retryAfter()).isEqualTo(now.plus(Duration.ofHours(24)));
    }

    private FirebaseNotificationPushSender sender(Duration deadline) {
        return new FirebaseNotificationPushSender(messaging, "https://example.com", Clock.fixed(now, ZoneOffset.UTC),
                deadline);
    }

    private void failWith(MessagingErrorCode code, IncomingHttpResponse response) {
        var exception = mock(FirebaseMessagingException.class);
        when(exception.getMessagingErrorCode()).thenReturn(code);
        when(exception.getHttpResponse()).thenReturn(response);
        when(messaging.sendAsync(any(Message.class))).thenReturn(ApiFutures.immediateFailedFuture(exception));
    }
}
