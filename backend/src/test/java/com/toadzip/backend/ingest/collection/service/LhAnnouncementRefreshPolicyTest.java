package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
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
            Duration.ofHours(24),
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
                Duration.ofHours(24),
                Period.ofDays(30)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("최근 종료 LH 공고 재수집 주기는 진행 중 공고보다 짧을 수 없습니다.");
    }

    @Test
    void 최신_LH_목록에서_확인한_마감이_먼_공고는_하루마다_전체_확인한다() {
        MyHomeAnnouncementSource source = source("20261001");
        source.markCollectedAt(CLOCK.instant().minusSeconds(60));
        var request = new LhAnnouncementRequest(
                "100", "03", "06", "06", "063"
        );
        var candidate = new Candidate(
                "announcement-100", "source", request, CLOCK.instant(), CLOCK.instant()
        );

        assertThat(policy.scheduledRefreshTtl(source, candidate)).contains(Duration.ofHours(24));
    }

    @Test
    void 마감이_이틀_이내이거나_알수_없으면_최신_목록이_있어도_6시간마다_확인한다() {
        for (String endDate : new String[] {"20260919", "20260921", "invalid"}) {
            MyHomeAnnouncementSource source = source(endDate);
            source.markCollectedAt(CLOCK.instant().minusSeconds(60));
            assertThat(policy.scheduledRefreshTtl(source, candidate(CLOCK.instant()))).contains(ACTIVE_TTL);
        }
    }

    @Test
    void 마이홈보다_오래된_목록이나_6시간_지난_목록은_하루_주기를_적용하지_않는다() {
        MyHomeAnnouncementSource source = source("20261001");
        source.markCollectedAt(CLOCK.instant());
        assertThat(policy.scheduledRefreshTtl(source, candidate(CLOCK.instant().minusSeconds(1))))
                .contains(ACTIVE_TTL);
        source.markCollectedAt(CLOCK.instant().minus(Duration.ofHours(7)));
        assertThat(policy.scheduledRefreshTtl(source, candidate(CLOCK.instant().minus(ACTIVE_TTL))))
                .contains(ACTIVE_TTL);
        assertThat(policy.scheduledRefreshTtl(source, candidate(null))).contains(ACTIVE_TTL);
    }

    private Candidate candidate(Instant collectedAt) {
        return new Candidate("announcement-100", "source",
                new LhAnnouncementRequest("100", "03", "06", "06", "063"), collectedAt, collectedAt);
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
