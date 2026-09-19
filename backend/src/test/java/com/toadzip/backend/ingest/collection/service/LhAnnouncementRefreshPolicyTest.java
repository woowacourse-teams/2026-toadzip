package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSourceSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class LhAnnouncementRefreshPolicyTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-19T03:00:00Z"),
            ZoneId.of("Asia/Seoul")
    );
    private static final Duration ACTIVE_TTL = Duration.ofHours(6);
    private static final Duration RECENT_ENDED_TTL = Duration.ofHours(24);

    private final LhAnnouncementRefreshPolicy policy = new LhAnnouncementRefreshPolicy(
            CLOCK,
            ACTIVE_TTL,
            RECENT_ENDED_TTL,
            Period.ofDays(30)
    );

    @Test
    void 종료일_당일까지는_진행_중_주기를_적용한다() {
        assertThat(policy.scheduledRefreshTtl(source("20260919"))).contains(ACTIVE_TTL);
        assertThat(policy.scheduledRefreshTtl(source("2026.09.20"))).contains(ACTIVE_TTL);
    }

    @Test
    void 종료_후_30일까지는_최근_종료_주기를_적용한다() {
        assertThat(policy.scheduledRefreshTtl(source("2026-09-18"))).contains(RECENT_ENDED_TTL);
        assertThat(policy.scheduledRefreshTtl(source("20260820"))).contains(RECENT_ENDED_TTL);
    }

    @Test
    void 종료_후_30일이_지나면_정기_수집에서_제외한다() {
        assertThat(policy.scheduledRefreshTtl(source("20260819"))).isEmpty();
    }

    @Test
    void 종료일이_없거나_해석되지_않으면_갱신_누락을_막기_위해_진행_중_주기를_적용한다() {
        assertThat(policy.scheduledRefreshTtl(source(null))).contains(ACTIVE_TTL);
        assertThat(policy.scheduledRefreshTtl(source("20260230"))).contains(ACTIVE_TTL);
    }

    @Test
    void 최근_종료_주기는_진행_중_공고보다_짧게_설정할_수_없다() {
        assertThatThrownBy(() -> new LhAnnouncementRefreshPolicy(
                CLOCK,
                Duration.ofHours(6),
                Duration.ofHours(1),
                Period.ofDays(30)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("최근 종료 LH 공고 재수집 주기는 진행 중 공고보다 짧을 수 없습니다.");
    }

    private MyHomeAnnouncementSource source(String endDate) {
        return MyHomeAnnouncementSource.from(0, new MyHomeAnnouncementSourceSnapshot(
                "announcement-100", 1, "모집중", "공고", "LH", null, "행복주택", null,
                null, null, null, endDate, null,
                "https://apply.lh.or.kr/panDetail?panId=100&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=06",
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null
        ));
    }
}
