package com.toadzip.backend.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationRetryPolicyTest {
    private final NotificationRetryPolicy policy = new NotificationRetryPolicy();
    private final Instant now = Instant.parse("2026-10-13T00:00:00Z");

    @Test
    void 지수_재시도는_지터와_한시간_상한을_적용한다() {
        assertThat(policy.nextAttemptAt(now, 1, null, Duration.ofSeconds(3)))
                .isEqualTo(Instant.parse("2026-10-13T00:01:03Z"));
        assertThat(policy.nextAttemptAt(now, 2, null, Duration.ZERO))
                .isEqualTo(Instant.parse("2026-10-13T00:02:00Z"));
        assertThat(policy.nextAttemptAt(now, 8, null, Duration.ofSeconds(15)))
                .isEqualTo(Instant.parse("2026-10-13T01:00:00Z"));
    }

    @Test
    void 서버가_요청한_재시도_하한을_지킨다() {
        assertThat(policy.nextAttemptAt(now, 1, now.plusSeconds(7200), Duration.ZERO))
                .isEqualTo(Instant.parse("2026-10-13T02:00:00Z"));
    }

    @Test
    void 여덟번째_실패나_발생후_24시간에는_종료한다() {
        assertThat(policy.isExhausted(7, now, now.plusSeconds(86399))).isFalse();
        assertThat(policy.isExhausted(8, now, now)).isTrue();
        assertThat(policy.isExhausted(1, now, now.plusSeconds(86400))).isTrue();
    }
}
