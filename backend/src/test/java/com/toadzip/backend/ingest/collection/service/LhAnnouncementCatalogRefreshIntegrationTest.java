package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.fixture.dto.LhAnnouncementCatalogPage.Entry;
import com.toadzip.backend.ingest.collection.fixture.repository.LhAnnouncementCatalogSourceFixtures;
import com.toadzip.backend.ingest.collection.fixture.repository.LhAnnouncementDetailSourceFixtures;
import com.toadzip.backend.ingest.collection.fixture.repository.LhCatalogStorageFixtures;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementSourceFixtures;
import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.projection.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.lh.detail.repository.LhAnnouncementDetailSourceReader;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQueryApiRepository;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQuerySourceRepository;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementExternalCollectionService;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.failure.repository.ExternalDataCollectionFailureRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
class LhAnnouncementCatalogRefreshIntegrationTest {

    private static final Instant FIRST = Instant.parse("2026-09-25T00:00:00Z");
    private static final ExternalDataSource DETAIL = ExternalDataSource.LH_ANNOUNCEMENT_DETAIL;

    @Autowired
    private LhAnnouncementExternalCollectionService service;
    @Autowired
    private MyHomeAnnouncementSourceFixtures sources;
    @Autowired
    private LhCatalogStorageFixtures catalogStore;
    @Autowired
    private LhAnnouncementCollectionCandidateResolver candidateResolver;
    @Autowired
    private LhAnnouncementCatalogSourceFixtures catalog;
    @Autowired
    private LhAnnouncementCollectionLinkRepository links;
    @Autowired
    private LhAnnouncementDetailSourceFixtures details;

    @Autowired
    private LhAnnouncementQuerySourceRepository querySources;

    @Autowired
    private LhAnnouncementDetailSourceReader detailReader;

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ExternalDataCollectionFailureRepository failures;
    @MockitoBean
    private LhAnnouncementQueryApiRepository external;
    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM lh_announcement_supply_rows").update();
        jdbc.sql("DELETE FROM lh_announcement_detail_rows").update();
        jdbc.sql("DELETE FROM lh_announcement_query_parameters").update();
        jdbc.sql("DELETE FROM lh_announcement_query_sources").update();
        jdbc.sql("DELETE FROM source_collection_record_parameters WHERE record_id IN "
                + "(SELECT id FROM source_collection_records WHERE source IN "
                + "('LH_ANNOUNCEMENT_SUPPLY', 'LH_ANNOUNCEMENT_DETAIL'))").update();
        jdbc.sql("DELETE FROM source_collection_records WHERE source IN "
                + "('LH_ANNOUNCEMENT_SUPPLY', 'LH_ANNOUNCEMENT_DETAIL')").update();
        links.deleteAllInBatch();
        details.deleteAllInBatch();
        failures.deleteAllInBatch();
        catalog.deleteAllInBatch();
        sources.deleteAllInBatch();
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(FIRST);
        when(external.detail(any())).thenReturn(response());
    }

    @Test
    void 목록이_같고_방금_성공했어도_다음_수집에서_공급상세를_실제_조회한다() {
        sources.saveAll(List.of(source("100", 1, "20261030"), source("200", 1, "20261030")));
        catalogStore.store(List.of(entry("공고중")));
        assertThat(service.collect(DETAIL).externalApiCallCount()).isEqualTo(2);

        Instant nextCollection = FIRST.plusSeconds(1);
        observeAgain(nextCollection, "공고중");
        assertThat(service.collect(DETAIL).externalApiCallCount()).isEqualTo(2);
        assertThat(querySources.findAll().stream().filter(row -> row.getPanId().equals("100")).toList())
                .singleElement().satisfies(row -> assertThat(row.getCollectedAt()).isEqualTo(nextCollection));
        assertThat(links.count()).isEqualTo(2);
        assertThat(querySources.findAll()).filteredOn(source -> source.getPanId().equals("100"))
                .singleElement().extracting(source -> source.getCollectedAt()).isEqualTo(nextCollection);

        observeAgain(FIRST.plus(Duration.ofHours(24)).plusSeconds(1), "공고중");
        assertThat(service.collect(DETAIL).externalApiCallCount()).isEqualTo(2);
    }

    @Test
    void 목록_변경은_즉시_조회하고_실패하면_기존_원천과_성공시각을_보존하여_다시_시도한다() {
        sources.save(source("100", 1, "20261030"));
        catalogStore.store(List.of(entry("공고중")));
        assertThat(service.collect(DETAIL).externalApiCallCount()).isOne();
        Long originalId = currentDetails().getFirst().getId();
        Instant changedAt = FIRST.plusSeconds(60);
        observeAgain(changedAt, "정정공고중");
        when(external.detail(any())).thenThrow(new ExternalDataRequestException("불완전 응답"));

        assertThat(service.collect(DETAIL).failedRequestCount()).isOne();
        assertThat(currentDetails()).singleElement().satisfies(row ->
                assertThat(row.getId()).isEqualTo(originalId));
        assertThat(querySources.findAll()).singleElement().satisfies(row ->
                assertThat(row.getCollectedAt()).isEqualTo(FIRST));

        doReturn(response()).when(external).detail(any());
        assertThat(service.collect(DETAIL).externalApiCallCount()).isOne();
        assertThat(querySources.findAll()).singleElement().satisfies(row ->
                assertThat(row.getCollectedAt()).isEqualTo(changedAt));
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

    @Test
    void 목록으로_보정된_조회코드는_실제_호출과_체크포인트와_원천_연결에_동일하게_사용된다() {
        sources.save(source("100", 1, "20261030"));
        catalogStore.store(List.of(new Entry(new LhAnnouncementCatalogSnapshot(
                "100", "03", "06", "06", "064", "공고", "공고중", "", "", "20261030", "", ""
        ), "{}")));
        var expectedRequest = new LhAnnouncementRequest("100", "03", "06", "06", "064");
        String description = expectedRequest.requestDescription();
        String requestHash = LhAnnouncementQuery.requestHashOf(description);

        var report = service.collect(DETAIL);

        assertThat(report.externalApiCallCount()).isOne();
        assertThat(report.failedRequestCount()).isZero();
        verify(external).detail(new LhAnnouncementQuery(
                expectedRequest.panId(), expectedRequest.connectionSystemDivisionCode(),
                expectedRequest.upperAnnouncementTypeCode(), expectedRequest.announcementTypeCode(),
                expectedRequest.supplyInfoTypeCode()));
        assertThat(querySources.findAll()).singleElement().satisfies(checkpoint -> {
            assertThat(checkpoint.getRequestDescription()).isEqualTo(description);
            assertThat(checkpoint.getRequestHash()).isEqualTo(requestHash);
        });
        assertThat(links.findAll()).singleElement().satisfies(link -> {
            assertThat(link.getRequestDescription()).isEqualTo(description);
            assertThat(link.getRequestHash()).isEqualTo(requestHash);
        });
        assertThat(service.collect(DETAIL).externalApiCallCount()).isOne();
    }

    private List<LhAnnouncementDetailSource> currentDetails() {
        return querySources.findAll().stream()
                .filter(source -> source.getSource() == CollectionSource.LH_ANNOUNCEMENT_DETAIL)
                .flatMap(source -> detailReader.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                        source.getPanId(), source.getRequestHash()).stream()).toList();
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
                 "endDe":"%s",
                 "url":"https://apply.lh.or.kr/panDetail?panId=%s&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=06"}
                """.formatted(panId, houseSn, endDate, panId);
        var source = MyHomeAnnouncementSource.from(0,
                JsonMapper.builder().build().readValue(payload, MyHomeAnnouncementSourceSnapshot.class));
        source.markCollectedAt(FIRST);
        return source;
    }

    private JsonNode response() {
        String payload = """
                [{"resHeader":[{"SS_CODE":"Y","RS_DTTM":"20260925120000"}],
                  "dsSbd":[{"LCC_NT_NM":"테스트 단지"}]}]
                """;
        return JsonMapper.builder().build().readTree(payload);
    }
}
