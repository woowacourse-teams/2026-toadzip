package com.toadzip.backend.ingest.gh.collection.service;

import static com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementBoard.RENTAL_HOUSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures;
import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures.Row;
import com.toadzip.backend.ingest.collection.fixture.MutableClock;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementSourceRepository;
import com.toadzip.backend.ingest.failure.domain.ExternalDataFailureStatus;
import com.toadzip.backend.ingest.failure.repository.ExternalDataCollectionFailureRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
@Import(GhAnnouncementCollectionIntegrationTest.ClockConfiguration.class)
@SpringBootTest(properties = {"ingest.gh.request-interval=0s", "ingest.gh.lookback-days=0"})
@ActiveProfiles("test")
class GhAnnouncementCollectionIntegrationTest {

    private static final Row ROW = new Row("801", "국민임대", "다산 국민임대주택 예비입주자 모집공고", "2026-07-10");

    @Autowired
    private GhAnnouncementCollectionService service;
    @Autowired
    private GhAnnouncementSourceRepository sources;
    @Autowired
    private ExternalDataCollectionFailureRepository failures;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private MutableClock clock;
    @MockitoBean
    private GhAnnouncementExternalRepository externalRepository;

    @BeforeEach
    void housingTypeDetails() {
        when(externalRepository.fetchHousingTypeDetail(any(), any())).thenAnswer(invocation ->
                GhAnnouncementFixtures.housingTypeDetail(invocation.<Map<String, String>>getArgument(1).get("houseTyCd")));
    }

    @AfterEach
    void cleanUp() {
        sources.deleteAll();
        failures.deleteAll();
        clock.reset();
    }

    @Test
    void 수집한_원천은_파싱_실패에도_보존되고_다시_성공하면_실패가_해소된다() {
        String detail = GhAnnouncementFixtures.detail(RENTAL_HOUSE, ROW);
        when(externalRepository.fetchList(RENTAL_HOUSE, 1)).thenReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, 1, 1, ROW));
        when(externalRepository.fetchDetail(RENTAL_HOUSE, "801", "01")).thenReturn(detail);
        long announcementCount = count("announcements");
        long housingCount = count("housing_complexes");

        assertThat(service.collect().storedRowCount()).isOne();
        var first = sources.findBySourceKey("GH:801").orElseThrow();
        assertThat(first.getBoard()).isEqualTo(RENTAL_HOUSE);
        assertThat(first.getDistricts()).contains("\"recruitmentCount\":\"280\"").doesNotContain("<");
        assertThat(first.getReception()).contains("031-510-7100");
        assertThat(first.getDistricts()).contains("\"contractArea\":\"67.8448\"");
        assertThat(first.getOtherNotes()).isEqualTo("기타 사항은 공고문을 확인해 주시기 바랍니다.");
        assertThat(first.getAttachments()).contains("atchFileDtlSn", "모집공고문.pdf");

        // 정정으로 목록 제목이 바뀌면 다시 받는다. 이때 상세 구조가 바뀌어 읽지 못하면 저장값을 그대로 둔다.
        when(externalRepository.fetchList(RENTAL_HOUSE, 1)).thenReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, 1, 1,
                new Row("801", ROW.supplyType(), "[정정] " + ROW.title(), ROW.postedDate())));
        when(externalRepository.fetchDetail(RENTAL_HOUSE, "801", "01")).thenReturn("<html>변경된 구조</html>");
        clock.advance(Duration.ofMinutes(1));
        assertThat(service.collect().failedRequestCount()).isOne();
        var preserved = sources.findBySourceKey("GH:801").orElseThrow();
        assertThat(preserved.getDistricts()).isEqualTo(first.getDistricts());
        assertThat(preserved.getNotice()).isEqualTo(first.getNotice());
        assertThat(preserved.getAttachments()).isEqualTo(first.getAttachments());
        assertThat(preserved.getCollectedAt()).isEqualTo(first.getCollectedAt());
        assertThat(failures.findAllBySourceAndRequestDescriptionAndStatus(
                ExternalDataSource.GH_ANNOUNCEMENT, "pbancNo=801", ExternalDataFailureStatus.PENDING)).hasSize(1);

        doReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, 1, 1, ROW)).when(externalRepository).fetchList(RENTAL_HOUSE, 1);
        doReturn(detail).when(externalRepository).fetchDetail(RENTAL_HOUSE, "801", "01");
        var retried = service.collect();
        assertThat(retried.failedRequestCount()).isZero();
        assertThat(retried.skippedRequestCount()).isZero();
        assertThat(sources.findBySourceKey("GH:801").orElseThrow().getId()).isEqualTo(first.getId());
        assertThat(failures.findAllBySourceAndRequestDescriptionAndStatus(
                ExternalDataSource.GH_ANNOUNCEMENT, "pbancNo=801", ExternalDataFailureStatus.RESOLVED)).hasSize(1);
        assertThat(count("announcements")).isEqualTo(announcementCount);
        assertThat(count("housing_complexes")).isEqualTo(housingCount);
    }

    @Test
    void 일부만_읽어_저장한_공고는_다음_실행에서_다시_받고_바뀌지_않은_공고는_건너뛴다() {
        String detail = GhAnnouncementFixtures.detail(RENTAL_HOUSE, ROW);
        when(externalRepository.fetchList(RENTAL_HOUSE, 1)).thenReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, 1, 1, ROW));
        when(externalRepository.fetchDetail(RENTAL_HOUSE, "801", "01"))
                .thenReturn(detail.replace("pbancNo=801&atchFileSn", "pbancNo=900&atchFileSn"));

        assertThat(service.collect().failedRequestCount()).isOne();
        var partial = sources.findBySourceKey("GH:801").orElseThrow();
        assertThat(partial.getDistricts()).contains("다산 센트럴파크6단지");
        assertThat(partial.getAttachments()).isNull();

        doReturn(detail).when(externalRepository).fetchDetail(RENTAL_HOUSE, "801", "01");
        var retried = service.collect();
        assertThat(retried.skippedRequestCount()).isZero();
        assertThat(sources.findBySourceKey("GH:801").orElseThrow().getAttachments()).contains("모집공고문.pdf");
        assertThat(failures.findAllBySourceAndRequestDescriptionAndStatus(
                ExternalDataSource.GH_ANNOUNCEMENT, "pbancNo=801", ExternalDataFailureStatus.RESOLVED)).hasSize(1);

        String collectedAt = sources.findBySourceKey("GH:801").orElseThrow().getCollectedAt().toString();
        when(externalRepository.fetchList(RENTAL_HOUSE, 1)).thenReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, 1, 1, ROW)
                .replace("<td>접수마감", "<td>접수중").replace("<td>2026-10-20", "<td>2026-10-25"));
        clock.advance(Duration.ofMinutes(1));
        var skipped = service.collect();
        assertThat(skipped.skippedRequestCount()).isOne();
        assertThat(skipped.externalApiCallCount()).isOne();
        var refreshed = sources.findBySourceKey("GH:801").orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo("접수중");
        assertThat(refreshed.getClosingDate()).isEqualTo(LocalDate.parse("2026-10-25"));
        assertThat(refreshed.getCollectedAt().toString()).isEqualTo(collectedAt);
        assertThat(refreshed.getAttachments()).contains("모집공고문.pdf");
    }

    @Test
    void 다_받은_공고도_마지막_수집_뒤_7일이_지나면_상세를_다시_받는다() {
        when(externalRepository.fetchList(RENTAL_HOUSE, 1))
                .thenReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, 1, 1, ROW));
        when(externalRepository.fetchDetail(RENTAL_HOUSE, "801", "01"))
                .thenReturn(GhAnnouncementFixtures.detail(RENTAL_HOUSE, ROW));
        assertThat(service.collect().storedRowCount()).isOne();

        clock.advance(Duration.ofDays(6));
        assertThat(service.collect().skippedRequestCount()).isOne();
        verify(externalRepository, times(1)).fetchDetail(RENTAL_HOUSE, "801", "01");

        clock.advance(Duration.ofDays(2));
        var refetched = service.collect();
        assertThat(refetched.skippedRequestCount()).isZero();
        assertThat(refetched.storedRowCount()).isOne();
        verify(externalRepository, times(2)).fetchDetail(RENTAL_HOUSE, "801", "01");
        assertThat(sources.findBySourceKey("GH:801").orElseThrow().getCollectedAt()).isEqualTo(clock.instant());
        assertThat(service.collect().skippedRequestCount()).isOne();
    }

    @Test
    void 기한이_지나_다시_받는_공고는_상세를_못_읽어도_목록의_상태와_마감일을_반영한다() {
        when(externalRepository.fetchList(RENTAL_HOUSE, 1))
                .thenReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, 1, 1, ROW));
        when(externalRepository.fetchDetail(RENTAL_HOUSE, "801", "01"))
                .thenReturn(GhAnnouncementFixtures.detail(RENTAL_HOUSE, ROW));
        assertThat(service.collect().storedRowCount()).isOne();

        clock.advance(Duration.ofDays(8));
        when(externalRepository.fetchList(RENTAL_HOUSE, 1))
                .thenReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, 1, 1, ROW)
                .replace("<td>접수마감", "<td>접수중").replace("<td>2026-10-20", "<td>2026-10-25"));
        when(externalRepository.fetchDetail(RENTAL_HOUSE, "801", "01")).thenReturn("<html>변경된 구조</html>");

        assertThat(service.collect().failedRequestCount()).isOne();
        var source = sources.findBySourceKey("GH:801").orElseThrow();
        assertThat(source.getStatus()).isEqualTo("접수중");
        assertThat(source.getClosingDate()).isEqualTo(LocalDate.parse("2026-10-25"));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
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
