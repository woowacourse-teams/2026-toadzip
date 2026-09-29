package com.toadzip.backend.announcement.repository.external;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.TemporaryAttachment;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException.Reason;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

class AnnouncementAttachmentClientTest {
    private static final String SOURCE = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=68442951";
    private final HttpClient http = mock(HttpClient.class);
    private final AnnouncementAttachmentClient client = new AnnouncementAttachmentClient(http, Duration.ofMillis(100));

    @Test
    void 다운로드_응답의_본문을_보존하고_HTTP도_HTTPS로_요청한다() throws Exception {
        byte[] bytes = "%PDF-1.7 sample".getBytes(StandardCharsets.US_ASCII);
        respond(200, Map.of(), bytes);
        try (var file = client.read(SOURCE.replace("https:", "http:"))) {
            assertArrayEquals(bytes, file.prefix());
        }
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(), any());
        assertEquals(URI.create(SOURCE), request.getValue().uri());
        assertTrue(request.getValue().headers().firstValue("Cookie").isEmpty());
        assertTrue(request.getValue().headers().firstValue("Authorization").isEmpty());
    }

    @Test
    void 임의_호스트와_포트_자격증명은_연결하기_전에_거절한다() {
        for (String source : List.of("http://127.0.0.1/a", "https://apply.lh.or.kr.evil.test/a",
                "https://user@apply.lh.or.kr/a", "https://apply.lh.or.kr:444/a", "file:///etc/passwd",
                "https://[::1]/a", "not a url")) {
            assertEquals(Reason.UNSUPPORTED_SOURCE,
                    assertThrows(AttachmentUnavailableException.class, () -> client.read(source)).reason());
        }
        verifyNoInteractions(http);
    }

    @Test
    void 리다이렉트와_오류_응답은_본문을_읽지_않는다() {
        for (int status : List.of(302, 404, 500)) {
            respond(status, Map.of(), new byte[] {1});
            assertEquals(Reason.UPSTREAM_FAILURE,
                    assertThrows(AttachmentUnavailableException.class, () -> client.read(SOURCE)).reason());
        }
    }

    @Test
    void HTML_응답과_빈_본문을_파일로_반환하지_않는다() {
        respond(200, Map.of("Content-Type", List.of("text/html;charset=utf-8")), new byte[] {1});
        assertEquals(Reason.UPSTREAM_FAILURE,
                assertThrows(AttachmentUnavailableException.class, () -> client.read(SOURCE)).reason());
        respond(200, Map.of(), new byte[0]);
        assertEquals(Reason.UPSTREAM_FAILURE,
                assertThrows(AttachmentUnavailableException.class, () -> client.read(SOURCE)).reason());
    }

    @Test
    void 기존_30MiB를_넘는_파일도_100MiB까지_허용한다() throws Exception {
        respond(200, Map.of("Content-Length", List.of("36332388")), new byte[] {1});
        try (var file = client.read(SOURCE)) {
            assertArrayEquals(new byte[] {1}, file.prefix());
        }
    }

    @Test
    void 크기_헤더가_있으면_본문을_받기_전에_거절한다() {
        respond(200, Map.of("Content-Length", List.of("104857601")), new byte[] {1});
        assertEquals(Reason.TOO_LARGE,
                assertThrows(AttachmentUnavailableException.class, () -> client.read(SOURCE)).reason());
    }

    @Test
    void 길이_헤더가_없어도_본문을_수신하면서_크기를_제한한다() throws Exception {
        try (var file = new TemporaryAttachment(() -> {})) {
            LimitedAttachmentSubscriber subscriber = new LimitedAttachmentSubscriber(3, file);
            Flow.Subscription subscription = mock(Flow.Subscription.class);
            subscriber.onSubscribe(subscription);
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2})));
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {3, 4})));
            CompletionException error = assertThrows(CompletionException.class,
                    () -> subscriber.getBody().toCompletableFuture().join());
            assertEquals(Reason.TOO_LARGE, ((AttachmentUnavailableException) error.getCause()).reason());
            verify(subscription).cancel();
        }
    }

    @Test
    void 응답_헤더나_본문이_끝나지_않아도_전체_제한시간에_취소하고_슬롯을_반환한다() {
        for (int index = 0; index < 5; index++) {
            CompletableFuture<HttpResponse<TemporaryAttachment>> waiting = new CompletableFuture<>();
            when(http.sendAsync(any(), ArgumentMatchers.<HttpResponse.BodyHandler<TemporaryAttachment>>any()))
                    .thenReturn(waiting);
            long started = System.nanoTime();
            assertEquals(Reason.UPSTREAM_FAILURE,
                    assertThrows(AttachmentUnavailableException.class, () -> client.read(SOURCE)).reason());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
            assertTrue(waiting.isCancelled());
        }
    }

    @Test
    void 네트워크_오류는_파일_불러오기_실패로_반환한다() {
        when(http.sendAsync(any(), ArgumentMatchers.<HttpResponse.BodyHandler<TemporaryAttachment>>any()))
                .thenReturn(CompletableFuture.failedFuture(new java.io.IOException("connection failed")));
        assertEquals(Reason.UPSTREAM_FAILURE,
                assertThrows(AttachmentUnavailableException.class, () -> client.read(SOURCE)).reason());
    }

    @Test
    void 전송이_끝날_때까지_슬롯을_유지하고_닫으면_재사용한다() throws Exception {
        respond(200, Map.of(), new byte[] {1});
        var files = new java.util.ArrayList<TemporaryAttachment>();
        try {
            for (int index = 0; index < 4; index++) {
                files.add(client.read(SOURCE));
            }
            assertEquals(Reason.BUSY,
                    assertThrows(AttachmentUnavailableException.class, () -> client.read(SOURCE)).reason());
            files.getFirst().close();
            files.getFirst().close();
            try (var next = client.read(SOURCE)) {
                assertEquals(1, next.size());
                assertThrows(IOException.class, () -> files.getFirst().prefix());
                assertEquals(Reason.BUSY,
                        assertThrows(AttachmentUnavailableException.class, () -> client.read(SOURCE)).reason());
            }
        } finally {
            for (var file : files) {
                file.close();
            }
        }
    }

    @Test
    void 설정한_크기와_정확히_같은_본문은_보존하고_초과하면_슬롯을_반환한다() throws Exception {
        var small = new AnnouncementAttachmentClient(http, Duration.ofMillis(100), 3);
        respond(200, Map.of("Content-Length", List.of("1")), new byte[] {1, 2, 3, 4});
        for (int index = 0; index < 5; index++) {
            assertEquals(Reason.TOO_LARGE,
                    assertThrows(AttachmentUnavailableException.class, () -> small.read(SOURCE)).reason());
        }
        respond(200, Map.of(), new byte[] {1, 2, 3});
        try (var file = small.read(SOURCE)) {
            assertArrayEquals(new byte[] {1, 2, 3}, file.prefix());
        }
    }

    @Test
    void 시간초과_후_늦게_도착한_본문은_임시파일을_되살리지_않는다() {
        var subscribers = new java.util.ArrayList<HttpResponse.BodySubscriber<TemporaryAttachment>>();
        when(http.sendAsync(any(), ArgumentMatchers.<HttpResponse.BodyHandler<TemporaryAttachment>>any()))
                .thenAnswer(invocation -> {
                    HttpResponse.BodyHandler<TemporaryAttachment> handler = invocation.getArgument(1);
                    HttpResponse.ResponseInfo info = mock(HttpResponse.ResponseInfo.class);
                    when(info.statusCode()).thenReturn(200);
                    when(info.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
                    subscribers.add(handler.apply(info));
                    return new CompletableFuture<>();
                });
        for (int index = 0; index < 5; index++) {
            assertThrows(AttachmentUnavailableException.class, () -> client.read(SOURCE));
            var subscriber = subscribers.getLast();
            var subscription = mock(Flow.Subscription.class);
            subscriber.onSubscribe(subscription);
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1})));
            assertThrows(CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
            verify(subscription).cancel();
        }
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, Map<String, List<String>> headers, byte[] bytes) {
        when(http.sendAsync(any(), ArgumentMatchers.<HttpResponse.BodyHandler<TemporaryAttachment>>any()))
                .thenAnswer(invocation -> {
            HttpResponse.BodyHandler<TemporaryAttachment> handler = invocation.getArgument(1);
            HttpResponse.ResponseInfo info = mock(HttpResponse.ResponseInfo.class);
            when(info.statusCode()).thenReturn(status);
            when(info.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
            try {
                var subscriber = handler.apply(info);
                subscriber.onSubscribe(mock(Flow.Subscription.class));
                if (bytes.length > 0) {
                    subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
                }
                subscriber.onComplete();
                return subscriber.getBody().toCompletableFuture().thenApply(body -> {
                    HttpResponse<TemporaryAttachment> response = mock(HttpResponse.class);
                    when(response.body()).thenReturn(body);
                    return response;
                });
            } catch (RuntimeException error) {
                return CompletableFuture.failedFuture(error);
            }
        });
    }
}
