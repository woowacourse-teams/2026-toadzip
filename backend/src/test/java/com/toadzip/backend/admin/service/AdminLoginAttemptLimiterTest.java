package com.toadzip.backend.admin.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.toadzip.backend.admin.exception.AdminLoginAttemptLimitExceededException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AdminLoginAttemptLimiterTest {

    @Test
    void 같은_IP와_식별자의_여섯_번째_로그인_시도를_차단한다() {
        AdminLoginAttemptLimiter limiter = new AdminLoginAttemptLimiter(
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC)
        );

        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.registerAttempt("127.0.0.1", "admin");
        }

        assertThrows(
                AdminLoginAttemptLimitExceededException.class,
                () -> limiter.registerAttempt("127.0.0.1", "admin")
        );
    }

    @Test
    void 성공한_로그인_이후에는_로그인_시도_기록을_초기화한다() {
        AdminLoginAttemptLimiter limiter = new AdminLoginAttemptLimiter(
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC)
        );

        limiter.registerAttempt("127.0.0.1", "admin");
        limiter.reset("127.0.0.1", "admin");

        assertDoesNotThrow(() -> limiter.registerAttempt("127.0.0.1", "admin"));
    }

    @Test
    void 차단된_로그인_시도는_십분_후에_다시_허용한다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-27T00:00:00Z"));
        AdminLoginAttemptLimiter limiter = new AdminLoginAttemptLimiter(clock);

        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.registerAttempt("127.0.0.1", "admin");
        }
        assertThrows(
                AdminLoginAttemptLimitExceededException.class,
                () -> limiter.registerAttempt("127.0.0.1", "admin")
        );

        clock.advance(Duration.ofMinutes(10));

        assertDoesNotThrow(() -> limiter.registerAttempt("127.0.0.1", "admin"));
    }

    private static class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
