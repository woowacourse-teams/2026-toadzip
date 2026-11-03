package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.fixture.repository.CollectedSourceRows;
import com.toadzip.backend.ingest.collection.fixture.repository.LhAnnouncementCatalogSourceFixtures;
import com.toadzip.backend.ingest.collection.fixture.dto.LhAnnouncementCatalogPage.Entry;
import com.toadzip.backend.ingest.collection.fixture.repository.LhCatalogStorageFixtures;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementCollectionLink;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQueryApiRepository;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQuerySourceRepository;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementExternalCollectionService;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.failure.repository.ExternalDataCollectionFailureRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(IngestExecutionExtension.class)
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
class LhAnnouncementCollectionQueryCountTest {

    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
    private static final ExternalDataSource DETAIL = ExternalDataSource.LH_ANNOUNCEMENT_DETAIL;

    @Autowired
    private LhAnnouncementExternalCollectionService service;

    @Autowired
    private LhCatalogStorageFixtures catalogStore;

    @Autowired
    private LhAnnouncementCollectionLinkRepository links;

    @Autowired
    private LhAnnouncementQuerySourceRepository querySources;

    @Autowired
    private CollectedSourceRows fixtures;

    private long baselineSuccessCount;

    @Autowired
    private ExternalDataCollectionFailureRepository failures;

    @Autowired
    private LhAnnouncementCatalogSourceFixtures catalog;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private LhAnnouncementQueryApiRepository external;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        cleanUp();
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM lh_announcement_supply_rows").update();
        jdbc.sql("DELETE FROM lh_announcement_detail_rows").update();
        jdbc.sql("DELETE FROM lh_announcement_query_parameters").update();
        jdbc.sql("DELETE FROM lh_announcement_query_sources").update();
        jdbc.sql("DELETE FROM source_collection_record_parameters WHERE record_id IN "
                + "(SELECT id FROM source_collection_records WHERE source IN "
                + "('LH_ANNOUNCEMENT_SUPPLY', 'LH_ANNOUNCEMENT_DETAIL'))").update();
        jdbc.sql("DELETE FROM source_collection_records WHERE source IN "
                + "('LH_ANNOUNCEMENT_SUPPLY', 'LH_ANNOUNCEMENT_DETAIL')").update();
        failures.deleteAllInBatch();
        links.deleteAllInBatch();
        catalog.deleteAllInBatch();
    }

    @Test
    void 최근_성공_원천이_있어도_공고목록의_공고마다_실행마다_한_번_조회한다() {
        prepareCache(32);
        when(external.detail(any())).thenReturn(response());

        for (int execution = 1; execution <= 2; execution++) {
            var result = service.collect(DETAIL);
            assertThat(result.failedRequestCount()).isZero();
            assertThat(result.externalApiCallCount()).isEqualTo(32);
            assertThat(result.successfulRequestCount()).isEqualTo(32);
            assertThat(recordCount("SUCCESS")).isEqualTo(32L * execution);
            assertThat(links.count()).isEqualTo(32);
        }
        verify(external, times(64)).detail(any());
    }

    @Test
    void 요청_실패는_한_번_기록하고_성공_원천과_연결을_보존한_뒤_다음_실행에서_재조회한다() {
        prepareCache(1);
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        when(external.detail(any())).thenThrow(new ExternalDataRequestException("응답 검증 실패"));

        var failed = service.collect(DETAIL);

        assertThat(failed.externalApiCallCount()).isOne();
        assertThat(failed.failedRequestCount()).isOne();
        assertThat(recordCount("FAILED")).isOne();
        assertThat(links.findAll()).singleElement().satisfies(link ->
                assertThat(link.getCompletedAt()).isEqualTo(NOW));
        assertThat(querySources.findAll()).singleElement().satisfies(checkpoint ->
                assertThat(checkpoint.getCollectedAt()).isEqualTo(NOW));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lh_announcement_detail_rows").query(Long.class).single()).isOne();

        doReturn(response()).when(external).detail(any());
        var recovered = service.collect(DETAIL);
        assertThat(recovered.externalApiCallCount()).isOne();
        assertThat(recordCount("FAILED")).isOne();
        assertThat(recordCount("SUCCESS")).isOne();
        assertThat(links.findAll()).singleElement().satisfies(link ->
                assertThat(link.getCompletedAt()).isEqualTo(NOW.plusSeconds(1)));
        verify(external, times(2)).detail(any());
    }

    @ParameterizedTest
    @EnumSource(value = ExternalDataSource.class, names = {"LH_ANNOUNCEMENT_SUPPLY", "LH_ANNOUNCEMENT_DETAIL"})
    void 성공_직후_재실행_실패는_성공_원천과_기록을_보존한다(ExternalDataSource target) {
        prepareCache(1);
        when(external.detail(any())).thenReturn(response());
        when(external.supply(any())).thenReturn(response());
        assertThat(service.collect(target).successfulRequestCount()).isOne();
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        org.mockito.Mockito.reset(external);
        if (target == DETAIL) {
            when(external.detail(any())).thenThrow(new ExternalDataRequestException("재조회 실패"));
        }
        if (target == ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY) {
            when(external.supply(any())).thenThrow(new ExternalDataRequestException("재조회 실패"));
        }

        var failed = service.collect(target);

        assertThat(failed.externalApiCallCount()).isOne();
        assertThat(failed.failedRequestCount()).isOne();
        assertThat(recordCount("SUCCESS")).isOne();
        assertThat(recordCount("FAILED")).isOne();
        assertThat(jdbc.sql("SELECT collected_at FROM lh_announcement_query_sources WHERE source = :source")
                .param("source", target.name()).query(Instant.class).single()).isEqualTo(NOW);
    }

    private long recordCount(String status) {
        long count = jdbc.sql("SELECT COUNT(*) FROM source_collection_records WHERE status = :status "
                + "AND source IN ('LH_ANNOUNCEMENT_SUPPLY', 'LH_ANNOUNCEMENT_DETAIL')")
                .param("status", status).query(Long.class).single();
        if (status.equals("SUCCESS")) {
            return count - baselineSuccessCount;
        }
        return count;
    }

    private JsonNode response() {
        return JsonMapper.builder().build().readTree("""
                [{"resHeader":[{"SS_CODE":"Y"}], "dsSbd":[{"LCC_NT_NM":"테스트 단지"}],
                  "dsList01":[{"SBD_LGO_NM":"테스트 단지","HTY_NNA":"46A","SUM_HSH_CNT":"10"}]}]
                """);
    }

    private void prepareCache(int count) {
        List<Entry> entries = new ArrayList<>();
        List<LhAnnouncementCollectionLink> completedLinks = new ArrayList<>();
        Map<String, String> completedRequests = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String panId = Integer.toString(100 + index);
            String request = new LhAnnouncementRequest(panId, "03", "06", "06", "063").requestDescription();
            entries.add(new Entry(new LhAnnouncementCatalogSnapshot(panId, "03", "06", "06", "063",
                    "공고", "공고중", "", "", "2026.10.30", "", ""), "{}"));
            completedLinks.add(LhAnnouncementCollectionLink.complete(DETAIL,
                    LhAnnouncementCollectionCandidateResolver.CATALOG_SOURCE_PREFIX + "03:06:06:" + panId,
                    request, panId, NOW));
            completedRequests.put(request, panId);
        }
        catalogStore.store(entries);
        links.saveAll(completedLinks);
        completedRequests.forEach((request, pan) -> {
            long parent = fixtures.querySource(DETAIL.name(), request, NOW);
            jdbc.sql("INSERT INTO lh_announcement_detail_rows (source_id, source_order, collected_at, "
                    + "dataset_type, complex_name) VALUES (?, 0, ?, 'COMPLEX', '테스트 단지')")
                    .params(parent, Timestamp.from(NOW)).update();
        });
        baselineSuccessCount = completedRequests.size();
    }
}
