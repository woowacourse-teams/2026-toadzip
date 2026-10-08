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
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementSourceFixtures;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementCollectionLink;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQueryApiRepository;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQuerySourceRepository;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementExternalCollectionService;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.failure.repository.ExternalDataCollectionFailureRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
class LhAnnouncementCollectionQueryCountTest {

    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
    private static final ExternalDataSource DETAIL = ExternalDataSource.LH_ANNOUNCEMENT_DETAIL;

    @Autowired
    private LhAnnouncementExternalCollectionService service;

    @Autowired
    private MyHomeAnnouncementSourceFixtures sources;

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
        sources.deleteAllInBatch();
    }

    @ParameterizedTest
    @CsvSource({"32,true", "256,true", "501,true", "32,false", "501,false"})
    void 최근_성공_원천이_있어도_배치에_걸친_공유_요청은_실행마다_한_번_조회한다(
            int count, boolean sharedRequest
    ) {
        prepareCache(count, sharedRequest);
        when(external.detail(any())).thenReturn(response());
        int requestCount = count;
        if (sharedRequest) {
            requestCount = 1;
        }

        for (int execution = 1; execution <= 2; execution++) {
            var result = service.collect(DETAIL);
            assertThat(result.failedRequestCount()).isZero();
            assertThat(result.externalApiCallCount()).isEqualTo(requestCount);
            assertThat(result.successfulRequestCount()).isEqualTo(requestCount);
            assertThat(recordCount("SUCCESS")).isEqualTo((long) requestCount * execution);
            assertThat(links.count()).isEqualTo(count);
        }
        verify(external, times(requestCount * 2)).detail(any());
    }
    @Test
    void 배치를_넘는_공유_요청의_실패는_한_번_기록하고_다음_실행에서_재조회한다() {
        prepareCache(501, true);
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        when(external.detail(any())).thenThrow(new ExternalDataRequestException("응답 검증 실패"));

        var failed = service.collect(DETAIL);

        assertThat(failed.externalApiCallCount()).isOne();
        assertThat(failed.failedRequestCount()).isOne();
        assertThat(recordCount("FAILED")).isOne();
        assertThat(links.findAll()).hasSize(501).allSatisfy(link ->
                assertThat(link.getCompletedAt()).isEqualTo(NOW));
        assertThat(querySources.findAll()).singleElement().satisfies(checkpoint ->
                assertThat(checkpoint.getCollectedAt()).isEqualTo(NOW));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lh_announcement_detail_rows").query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lh_announcement_query_sources").query(Long.class).single()).isOne();

        doReturn(response()).when(external).detail(any());
        var recovered = service.collect(DETAIL);
        assertThat(recovered.externalApiCallCount()).isOne();
        assertThat(recordCount("FAILED")).isOne();
        assertThat(recordCount("SUCCESS")).isOne();
        assertThat(links.findAll()).hasSize(501).allSatisfy(link ->
                assertThat(link.getCompletedAt()).isEqualTo(NOW.plusSeconds(1)));
        verify(external, times(2)).detail(any());
    }

    @ParameterizedTest
    @EnumSource(value = ExternalDataSource.class, names = {"LH_ANNOUNCEMENT_SUPPLY", "LH_ANNOUNCEMENT_DETAIL"})
    void 성공_직후_재실행_실패는_성공_원천과_기록을_보존한다(ExternalDataSource target) {
        prepareCache(1, true);
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

    private void prepareCache(int count, boolean sharedRequest) {
        List<MyHomeAnnouncementSource> rows = new ArrayList<>();
        List<LhAnnouncementCollectionLink> completedLinks = new ArrayList<>();
        Map<String, String> completedRequests = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String identifier = "announcement-" + index;
            String panId = "100";
            if (!sharedRequest) {
                panId = Integer.toString(100 + index);
            }
            String request = new LhAnnouncementRequest(panId, "03", "06", "06", "063").requestDescription();
            rows.add(source(identifier, panId));
            completedLinks.add(LhAnnouncementCollectionLink.complete(DETAIL, identifier, request, panId, NOW));
            completedRequests.putIfAbsent(request,
                    panId);
        }
        sources.saveAll(rows);
        links.saveAll(completedLinks);
        completedRequests.forEach((request, pan) -> {
            long parent = fixtures.querySource(DETAIL.name(), request, NOW);
            jdbc.sql("INSERT INTO lh_announcement_detail_rows (source_id, source_order, collected_at, "
                    + "dataset_type, complex_name) VALUES (?, 0, ?, 'COMPLEX', '테스트 단지')")
                    .params(parent, java.sql.Timestamp.from(NOW)).update();
        });
        baselineSuccessCount = completedRequests.size();
    }

    private MyHomeAnnouncementSource source(String identifier, String panId) {
        String payload = """
                {"pblancId":"%s","houseSn":1,"suplyInsttNm":"LH","suplyTyNm":"행복주택",
                 "endDe":"20261030",
                 "url":"https://apply.lh.or.kr/panDetail?panId=%s&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=06"}
                """.formatted(identifier, panId);
        return MyHomeAnnouncementSource.from(0,
                JsonMapper.builder().build().readValue(payload, MyHomeAnnouncementSourceSnapshot.class));
    }

}
