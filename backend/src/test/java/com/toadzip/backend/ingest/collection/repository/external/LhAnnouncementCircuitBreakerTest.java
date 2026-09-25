package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.exception.exception.LhAnnouncementUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LhAnnouncementCircuitBreakerTest {

    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-09-25T00:00:00Z");
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LhAnnouncementCircuitBreaker circuit = new LhAnnouncementCircuitBreaker(clock, registry);

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(now);
    }

    @Test
    void 연속_연결_장애_다섯_번이면_다음_요청은_외부_호출_없이_거절한다() {
        openCircuit();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> circuit.execute(calls::incrementAndGet))
                .isInstanceOf(LhAnnouncementUnavailableException.class);

        assertThat(calls).hasValue(0);
        assertThat(registry.get("ingest.lh.circuit.opened").counter().count()).isEqualTo(1);
        assertThat(registry.get("ingest.lh.circuit.rejected").counter().count()).isEqualTo(1);
    }

    @Test
    void 성공은_연속_장애를_초기화하고_응답_데이터_오류는_차단하지_않는다() {
        for (int i = 0; i < 4; i++) {
            failConnection();
        }
        assertThat(circuit.execute(() -> "ok")).isEqualTo("ok");
        for (int i = 0; i < 4; i++) {
            failConnection();
        }
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> circuit.execute(() -> {
                throw new ExternalDataRequestException("잘못된 응답 데이터");
            })).isInstanceOf(ExternalDataRequestException.class);
        }
        assertThat(circuit.execute(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void 요청_제한은_즉시_차단한다() {
        assertThatThrownBy(() -> circuit.execute(() -> {
            throw ExternalDataRequestException.rateLimited("요청 제한");
        })).isInstanceOf(ExternalDataRequestException.class);

        assertThatThrownBy(() -> circuit.execute(() -> "should not run"))
                .isInstanceOf(LhAnnouncementUnavailableException.class);
    }

    @Test
    void 삼십_초_후에는_단_한_건만_복구를_확인하고_성공하면_호출을_재개한다() throws Exception {
        openCircuit();
        when(clock.instant()).thenReturn(now.plusSeconds(29));
        assertThatThrownBy(() -> circuit.execute(() -> "too early"))
                .isInstanceOf(LhAnnouncementUnavailableException.class);
        when(clock.instant()).thenReturn(now.plusSeconds(30));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var probe = executor.submit(() -> circuit.execute(() -> {
                started.countDown();
                await(release);
                return "recovered";
            }));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> circuit.execute(() -> "second probe"))
                        .isInstanceOf(LhAnnouncementUnavailableException.class);
            }
            finally {
                release.countDown();
            }
            assertThat(probe.get(5, TimeUnit.SECONDS)).isEqualTo("recovered");
            assertThat(circuit.execute(() -> "resumed")).isEqualTo("resumed");
        }
    }

    @Test
    void 복구_확인이_실패하면_다시_삼십_초_동안_차단한다() {
        openCircuit();
        when(clock.instant()).thenReturn(now.plusSeconds(30));
        failConnection();
        when(clock.instant()).thenReturn(now.plusSeconds(59));
        assertThatThrownBy(() -> circuit.execute(() -> "too early"))
                .isInstanceOf(LhAnnouncementUnavailableException.class);
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        assertThat(circuit.execute(() -> "recovered")).isEqualTo("recovered");
    }

    @Test
    void 차단_전에_시작한_느린_요청의_성공은_열린_회로를_닫지_않는다() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> circuit.execute(() -> {
                started.countDown();
                await(release);
                return "late success";
            }));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                openCircuit();
            }
            finally {
                release.countDown();
            }
            assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo("late success");
            assertThatThrownBy(() -> circuit.execute(() -> "still blocked"))
                    .isInstanceOf(LhAnnouncementUnavailableException.class);
        }
    }

    private void openCircuit() {
        for (int i = 0; i < 5; i++) {
            failConnection();
        }
    }

    private void failConnection() {
        assertThatThrownBy(() -> circuit.execute(() -> {
            throw ExternalDataRequestException.retryable("연결 실패");
        })).isInstanceOf(ExternalDataRequestException.class);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 요청이 완료되지 않았습니다.");
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
