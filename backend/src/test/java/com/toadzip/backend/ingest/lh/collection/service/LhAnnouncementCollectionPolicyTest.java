package com.toadzip.backend.ingest.lh.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementCollectionPolicy;
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
    @ValueSource(strings = {"2026.10.01", "2026.09.19", "2026.08.20", "", "2026.02.30", "20260819", "invalid"})
    void 진행중과_최근_30일_종료와_해석불가_공고는_수집_대상이다(String endDate) {
        assertThat(policy.isCollectionTarget(source(endDate))).isTrue();
    }

    @Test
    void 종료_후_30일을_초과한_공고는_수집_대상에서_제외한다() {
        assertThat(policy.isCollectionTarget(source("2026.08.19"))).isFalse();
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

    private LhAnnouncementCatalogSourceView source(String closingDate) {
        return LhAnnouncementCatalogSourceView.read(1L, Instant.EPOCH, Instant.EPOCH, true,
                new LhAnnouncementCatalogSnapshot("100", "03", "06", "07", "062", "공고", "공고중",
                        "2026.09.01", "2026.09.01", closingDate, "https://apply.lh.or.kr/a", null));
    }
}
