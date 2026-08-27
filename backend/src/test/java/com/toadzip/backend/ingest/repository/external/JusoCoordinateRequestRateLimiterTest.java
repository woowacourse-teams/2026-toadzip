package com.toadzip.backend.ingest.repository.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class JusoCoordinateRequestRateLimiterTest {

    @Test
    void 좌표_API_호출_간격을_오백_밀리초_이상으로_제한한다() {
        AtomicLong nanoTime = new AtomicLong();
        List<Duration> waits = new ArrayList<>();
        JusoCoordinateRequestRateLimiter limiter = new JusoCoordinateRequestRateLimiter(
                nanoTime::get,
                duration -> {
                    waits.add(duration);
                    nanoTime.addAndGet(duration.toNanos());
                }
        );

        limiter.acquire();
        limiter.acquire();
        limiter.acquire();

        assertThat(waits).containsExactly(Duration.ofMillis(500), Duration.ofMillis(500));
    }

    @Test
    void 제한_시간보다_늦게_깨어나면_실제_시각부터_다음_호출_간격을_계산한다() {
        AtomicLong nanoTime = new AtomicLong();
        List<Duration> waits = new ArrayList<>();
        JusoCoordinateRequestRateLimiter limiter = new JusoCoordinateRequestRateLimiter(
                nanoTime::get,
                duration -> {
                    waits.add(duration);
                    nanoTime.addAndGet(duration.toNanos());
                    if (waits.size() == 1) {
                        nanoTime.addAndGet(Duration.ofMillis(500).toNanos());
                    }
                }
        );

        limiter.acquire();
        limiter.acquire();
        limiter.acquire();

        assertThat(waits).containsExactly(Duration.ofMillis(500), Duration.ofMillis(500));
    }
}
