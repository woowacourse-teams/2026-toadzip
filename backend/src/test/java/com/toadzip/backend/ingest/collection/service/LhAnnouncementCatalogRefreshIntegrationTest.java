package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementCatalogPage.Entry;
import com.toadzip.backend.ingest.collection.repository.ExternalDataCollectionFailureRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCatalogSourceRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCatalogStore;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionCheckpointRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementDetailSourceRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementSourceRepository;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
class LhAnnouncementCatalogRefreshIntegrationTest {

    private static final Instant FIRST = Instant.parse("2026-09-25T00:00:00Z");
    private static final ExternalDataSource DETAIL = ExternalDataSource.LH_ANNOUNCEMENT_DETAIL;

    @Autowired
    private LhAnnouncementExternalCollectionService service;
    @Autowired
    private MyHomeAnnouncementSourceRepository sources;
    @Autowired
    private LhAnnouncementCatalogStore catalogStore;
    @Autowired
    private LhAnnouncementCollectionCandidateResolver candidateResolver;
    @Autowired
    private LhAnnouncementCatalogSourceRepository catalog;
    @Autowired
    private LhAnnouncementCollectionCheckpointRepository checkpoints;
    @Autowired
    private LhAnnouncementCollectionLinkRepository links;
    @Autowired
    private LhAnnouncementDetailSourceRepository details;
    @Autowired
    private ExternalDataCollectionFailureRepository failures;
    @MockitoBean
    private LhAnnouncementExternalRepository external;
    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        links.deleteAllInBatch();
        checkpoints.deleteAllInBatch();
        details.deleteAllInBatch();
        failures.deleteAllInBatch();
        catalog.deleteAllInBatch();
        sources.deleteAllInBatch();
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(FIRST);
        when(external.fetchDetail(any())).thenReturn(response());
    }

    @Test
    void 변경없는_공고는_6시간에_건너뛰고_24시간에_조회하며_목록에_없는_공고는_계속_수집한다() {
        sources.saveAll(List.of(source("100", 1, "20261030"), source("200", 1, "20261030")));
        catalogStore.store(List.of(entry("공고중")));
        assertThat(service.collect(DETAIL).externalApiCallCount()).isEqualTo(2);

        observeAgain(FIRST.plus(Duration.ofHours(6)).plusSeconds(1), "공고중");
        assertThat(service.collect(DETAIL).externalApiCallCount()).isOne();
        assertThat(checkpoints.findAllBySourceAndPanIdOrderByCompletedAtDesc(DETAIL, "100"))
                .singleElement().satisfies(row -> assertThat(row.getCompletedAt()).isEqualTo(FIRST));
        assertThat(links.count()).isEqualTo(2);

        observeAgain(FIRST.plus(Duration.ofHours(24)).plusSeconds(1), "공고중");
        assertThat(service.collect(DETAIL).externalApiCallCount()).isEqualTo(2);
    }

    @Test
    void 목록_변경은_즉시_조회하고_실패하면_기존_원천과_성공시각을_보존하여_다시_시도한다() {
        sources.save(source("100", 1, "20261030"));
        catalogStore.store(List.of(entry("공고중")));
        assertThat(service.collect(DETAIL).externalApiCallCount()).isOne();
        Long originalId = details.findAll().getFirst().getId();
        Instant changedAt = FIRST.plusSeconds(60);
        observeAgain(changedAt, "정정공고중");
        when(external.fetchDetail(any())).thenThrow(new ExternalDataRequestException("불완전 응답"));

        assertThat(service.collect(DETAIL).failedRequestCount()).isOne();
        assertThat(details.findAll()).singleElement().satisfies(row ->
                assertThat(row.getId()).isEqualTo(originalId));
        assertThat(checkpoints.findAll()).singleElement().satisfies(row ->
                assertThat(row.getCompletedAt()).isEqualTo(FIRST));

        doReturn(response()).when(external).fetchDetail(any());
        assertThat(service.collect(DETAIL).externalApiCallCount()).isOne();
        assertThat(checkpoints.findAll()).singleElement().satisfies(row ->
                assertThat(row.getCompletedAt()).isEqualTo(changedAt));
    }

    @Test
    void 같은_공고의_한_단지라도_마감이_가까우면_6시간_주기를_적용한다() {
        sources.saveAll(List.of(source("100", 1, "20261030"), source("100", 2, "20260927")));
        catalogStore.store(List.of(entry("공고중")));
        assertThat(service.collect(DETAIL).externalApiCallCount()).isOne();

        observeAgain(FIRST.plus(Duration.ofHours(6)).plusSeconds(1), "공고중");
        assertThat(service.collect(DETAIL).externalApiCallCount()).isOne();
    }

    @Test
    void 조회_유형이_바뀐_경우_이전_목록_행은_후보_연결에_사용하지_않는다() {
        MyHomeAnnouncementSource source = sources.save(source("100", 1, "20261030"));
        catalogStore.store(List.of(entry("공고중")));
        assertThat(candidateResolver.resolve(source)).isInstanceOfSatisfying(Candidate.class, candidate ->
                assertThat(candidate.catalogCollectedAt()).isEqualTo(FIRST)
        );

        when(clock.instant()).thenReturn(FIRST.plusSeconds(60));
        catalogStore.store(List.of(new Entry(new LhAnnouncementCatalogSnapshot(
                "100", "03", "06", "07", "062", "공고", "공고중", "", "", "20261030", "", ""
        ), "{}")));

        assertThat(candidateResolver.resolve(source)).isInstanceOfSatisfying(Candidate.class, candidate -> {
            assertThat(candidate.catalogCollectedAt()).isNull();
            assertThat(candidate.request().announcementTypeCode()).isEqualTo("06");
        });
        assertThat(catalog.findAllByPanIdInAndPresentInLatestCatalogTrue(List.of("100")))
                .singleElement().satisfies(row -> assertThat(row.getAnnouncementTypeCode()).isEqualTo("07"));
        assertThat(catalog.count()).isEqualTo(2);
    }

    private void observeAgain(Instant now, String status) {
        when(clock.instant()).thenReturn(now);
        List<MyHomeAnnouncementSource> current = sources.findAll();
        current.forEach(source -> source.markCollectedAt(now));
        sources.saveAll(current);
        catalogStore.store(List.of(entry(status)));
    }

    private Entry entry(String status) {
        return new Entry(new LhAnnouncementCatalogSnapshot("100", "03", "06", "06", "063",
                "공고", status, "", "", "20261030", "", ""), "{}");
    }

    private MyHomeAnnouncementSource source(String panId, int houseSn, String endDate) {
        String payload = """
                {"pblancId":"%s","houseSn":%d,"suplyInsttNm":"한국토지주택공사","suplyTyNm":"행복주택",
                 "endDe":"%s","url":"https://apply.lh.or.kr/panDetail?panId=%s&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=06"}
                """.formatted(panId, houseSn, endDate, panId);
        var source = MyHomeAnnouncementSource.from(0,
                JsonMapper.builder().build().readValue(payload, MyHomeAnnouncementSourceSnapshot.class));
        source.markCollectedAt(FIRST);
        return source;
    }

    private ExternalDataResponse response() {
        String payload = """
                [{"resHeader":[{"SS_CODE":"Y","RS_DTTM":"20260925120000"}],
                  "dsSbd":[{"LCC_NT_NM":"테스트 단지"}]}]
                """;
        return new ExternalDataResponse(payload, JsonMapper.builder().build().readTree(payload));
    }
}
