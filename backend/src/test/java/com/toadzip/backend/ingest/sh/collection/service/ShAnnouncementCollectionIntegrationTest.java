package com.toadzip.backend.ingest.sh.collection.service;

import static com.toadzip.backend.ingest.collection.fixture.ShAnnouncementFixtures.detail;
import static com.toadzip.backend.ingest.collection.fixture.ShAnnouncementFixtures.mainList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.fixture.MutableClock;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementExternalRepository;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementSourceRepository;
import com.toadzip.backend.ingest.failure.domain.ExternalDataFailureStatus;
import com.toadzip.backend.ingest.failure.repository.ExternalDataCollectionFailureRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import java.time.Duration;
import java.time.Instant;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ExtendWith(IngestExecutionExtension.class)
@Import(ShAnnouncementCollectionIntegrationTest.ClockConfiguration.class)
@SpringBootTest(properties = {"ingest.sh.request-interval=0s", "ingest.sh.lookback-days=0"})
@ActiveProfiles("test")
class ShAnnouncementCollectionIntegrationTest {

    @Autowired
    private ShAnnouncementCollectionService service;
    @Autowired
    private ShAnnouncementSourceRepository sources;
    @Autowired
    private ExternalDataCollectionFailureRepository failures;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private MutableClock clock;
    @MockitoBean
    private ShAnnouncementExternalRepository externalRepository;

    @AfterEach
    void cleanUp() {
        sources.deleteAll();
        failures.deleteAll();
        clock.reset();
    }

    @Test
    void 일부만_읽은_공고는_다음_수집에서_다시_받고_다_받은_뒤에는_건너뛴다() {
        String detail = detail();
        String withoutAttachments = detail.replace("initParam.downList", "removed");
        when(externalRepository.fetchList(1)).thenReturn(singlePostList());
        when(externalRepository.fetchDetail("100")).thenReturn(withoutAttachments);
        long announcementCount = count("announcements");
        long housingCount = count("housing_complexes");

        var partial = service.collect();
        assertThat(partial.storedRowCount()).isOne();
        assertThat(partial.failedRequestCount()).isOne();
        var first = sources.findBySourceKey("SH:100").orElseThrow();
        Long sourceId = first.getId();
        assertThat(first.getBodyText()).contains("입주자 모집 안내", "신청 안내").doesNotContain("<", "\t", "\r");
        assertThat(first.getAttachments()).isNull();
        assertThat(pending()).hasSize(1);

        doReturn("<html>변경된 구조</html>").when(externalRepository).fetchDetail("100");
        assertThat(service.collect().failedRequestCount()).isOne();
        var preserved = sources.findBySourceKey("SH:100").orElseThrow();
        assertThat(preserved.getBodyText()).isEqualTo(first.getBodyText());
        assertThat(preserved.getCollectedAt()).isEqualTo(first.getCollectedAt());
        assertThat(pending()).hasSize(1);

        doReturn(detail).when(externalRepository).fetchDetail("100");
        assertThat(service.collect().failedRequestCount()).isZero();
        var refreshed = sources.findBySourceKey("SH:100").orElseThrow();
        assertThat(refreshed.getId()).isEqualTo(sourceId);
        assertThat(refreshed.getBodyText()).isEqualTo(first.getBodyText());
        assertThat(refreshed.getAttachments()).contains("fileSeq", "공고문", "innoFD.do");
        assertThat(pending()).isEmpty();

        var skipped = service.collect();
        assertThat(skipped.skippedRequestCount()).isOne();
        assertThat(skipped.externalApiCallCount()).isOne();
        verify(externalRepository, times(3)).fetchDetail("100");
        assertThat(count("announcements")).isEqualTo(announcementCount);
        assertThat(count("housing_complexes")).isEqualTo(housingCount);
    }

    @Test
    void 다_받은_게시글도_마지막_수집_뒤_7일이_지나면_상세를_다시_받는다() {
        when(externalRepository.fetchList(1)).thenReturn(singlePostList());
        when(externalRepository.fetchDetail("100")).thenReturn(detail());
        assertThat(service.collect().storedRowCount()).isOne();

        clock.advance(Duration.ofDays(6));
        assertThat(service.collect().skippedRequestCount()).isOne();
        verify(externalRepository, times(1)).fetchDetail("100");

        clock.advance(Duration.ofDays(2));
        var refetched = service.collect();
        assertThat(refetched.skippedRequestCount()).isZero();
        assertThat(refetched.storedRowCount()).isOne();
        verify(externalRepository, times(2)).fetchDetail("100");
        assertThat(sources.findBySourceKey("SH:100").orElseThrow().getCollectedAt()).isEqualTo(clock.instant());
        assertThat(service.collect().skippedRequestCount()).isOne();
    }

    private java.util.List<?> pending() {
        return failures.findAllBySourceAndRequestDescriptionAndStatus(
                ExternalDataSource.SH_ANNOUNCEMENT, "seq=100", ExternalDataFailureStatus.PENDING);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private String singlePostList() {
        var document = Jsoup.parse(mainList());
        document.select("#listTb tbody > tr").get(1).remove();
        document.selectFirst("#mainform .mentcount strong").text("1");
        return document.html();
    }

    @TestConfiguration
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock collectionClock() {
            return new MutableClock(Instant.parse("2026-10-05T00:00:00Z"));
        }
    }
}
