package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionPolicy;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LhAnnouncementCollectionPolicyTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-19T00:00:00Z"), ZoneOffset.UTC);
    private final LhAnnouncementCollectionPolicy policy = new LhAnnouncementCollectionPolicy(clock, Period.ofDays(30));

    @ParameterizedTest
    @ValueSource(strings = {"20261001", "2026.09.19", "2026-09-18", "20260820", "", "20260230", "invalid"})
    void 진행중과_최근_30일_종료와_해석불가_공고는_수집_대상이다(String endDate) {
        assertThat(policy.isCollectionTarget(source(endDate))).isTrue();
    }

    @Test
    void 종료_후_30일을_초과한_공고는_수집_대상에서_제외한다() {
        assertThat(policy.isCollectionTarget(source("20260819"))).isFalse();
    }

    @Test
    void 종료일이_없는_공고는_수집_대상이다() {
        assertThat(policy.isCollectionTarget(source(null))).isTrue();
    }

    @Test
    void 종료_공고_수집_범위는_양수여야_한다() {
        assertThatThrownBy(() -> new LhAnnouncementCollectionPolicy(clock, Period.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
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
