package com.toadzip.backend.streetview.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.streetview.exception.StreetViewCollectionException.Reason;
import com.toadzip.backend.streetview.exception.StreetViewCollectionException;
import com.toadzip.backend.streetview.service.StreetViewEventAdmission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;

class StreetViewEventBodyAdviceTest {
    private final StreetViewEventBodyAdvice advice = new StreetViewEventBodyAdvice(new StreetViewEventAdmission(
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new SimpleMeterRegistry(), 20, 1));

    @ParameterizedTest
    @ValueSource(longs = {-1, 1, 4096})
    void 선언된_길이와_무관하게_실제_바이트를_제한한다(long declared) {
        var headers = new HttpHeaders();
        if (declared >= 0) {
            headers.setContentLength(declared);
        }
        var stream = new ByteArrayInputStream(new byte[10_000]);
        assertThatThrownBy(() -> advice.beforeBodyRead(message(headers, stream), null, null, null))
                .isInstanceOfSatisfying(StreetViewCollectionException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo(Reason.PAYLOAD_TOO_LARGE));
        assertThat(stream.available()).isEqualTo(10_000 - 4097);
    }

    @Test
    void 빈_본문도_토큰을_소모하고_제한시_다음_본문을_읽지_않는다() {
        advice.handleEmptyBody(null, null, null, null, null);
        var stream = new ByteArrayInputStream(new byte[10]);
        assertThatThrownBy(() -> advice.beforeBodyRead(message(new HttpHeaders(), stream), null, null, null))
                .isInstanceOfSatisfying(StreetViewCollectionException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo(Reason.RATE_LIMIT));
        assertThat(stream.available()).isEqualTo(10);
    }

    private HttpInputMessage message(HttpHeaders headers, InputStream stream) {
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() throws IOException {
                return stream;
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }
}
