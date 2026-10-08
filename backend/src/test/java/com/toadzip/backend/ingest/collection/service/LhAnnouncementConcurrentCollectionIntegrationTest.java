package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.fixture.repository.LhAnnouncementDetailSourceFixtures;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementSourceFixtures;
import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.lh.detail.repository.LhAnnouncementDetailSourceReader;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQueryApiRepository;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementQuerySourceRepository;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementExternalCollectionService;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.failure.repository.ExternalDataCollectionFailureRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
class LhAnnouncementConcurrentCollectionIntegrationTest {

    @Autowired
    private LhAnnouncementExternalCollectionService service;

    @Autowired
    private MyHomeAnnouncementSourceFixtures sources;

    @Autowired
    private LhAnnouncementDetailSourceFixtures details;

    @Autowired
    private LhAnnouncementQuerySourceRepository querySources;

    @Autowired
    private LhAnnouncementDetailSourceReader detailReader;

    @Autowired
    private JdbcClient jdbc;


    @Autowired
    private LhAnnouncementCollectionLinkRepository links;

    @Autowired
    private ExternalDataCollectionFailureRepository failures;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private LhAnnouncementQueryApiRepository externalRepository;

    @BeforeEach
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
        links.deleteAllInBatch();
        details.deleteAllInBatch();
        failures.deleteAllInBatch();
        sources.deleteAllInBatch();
    }

    @Test
    void 병렬_수집은_독립_트랜잭션으로_성공_연결을_저장하고_다음_실행에서_모든_대상을_재조회한다() {
        sources.saveAll(List.of(source("a", "100"), source("b", "200"), source("c", "100")));
        CountDownLatch started = new CountDownLatch(2);
        when(externalRepository.detail(any())).thenAnswer(invocation -> {
            LhAnnouncementQuery request = invocation.getArgument(0);
            started.countDown();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            if (request.panId().equals("200")) {
                throw new ExternalDataRequestException("응답 형식 오류");
            }
            return response();
        });

        var first = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        assertThat(first.storedRowCount()).isOne();
        assertThat(first.failedRequestCount()).isOne();
        assertThat(first.externalApiCallCount()).isEqualTo(2);
        assertThat(currentDetails()).extracting(detail -> detail.getPanId()).containsExactly("100");
        assertThat(querySources.count()).isOne();
        assertThat(links.findAll()).extracting(link -> link.getSourceAnnouncementKey())
                .containsExactlyInAnyOrder("a", "c");
        assertThat(failures.count()).isOne();

        clearInvocations(externalRepository);
        doReturn(response()).when(externalRepository).detail(any());
        var retry = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        assertThat(retry.externalApiCallCount()).isEqualTo(2);
        assertThat(retry.failedRequestCount()).isZero();
        assertThat(currentDetails()).extracting(detail -> detail.getPanId())
                .containsExactlyInAnyOrder("100", "200");
        assertThat(querySources.count()).isEqualTo(2);
        assertThat(links.count()).isEqualTo(3);
        assertThat(meterRegistry.get("ingest.announcement.store")
                .tag("source", "LH_ANNOUNCEMENT_DETAIL").timer().count()).isEqualTo(3);
        assertThat(meterRegistry.get("ingest.external.request")
                .tags("source", "LH_ANNOUNCEMENT_DETAIL", "result", "completed").timer().count()).isEqualTo(3);
        assertThat(meterRegistry.get("ingest.external.request")
                .tags("source", "LH_ANNOUNCEMENT_DETAIL", "result", "failed").timer().count()).isOne();
        assertThat(meterRegistry.get("ingest.announcement.source.rows")
                .tags("source", "LH_ANNOUNCEMENT_DETAIL", "mode", "scheduled", "result", "read")
                .counter().count()).isEqualTo(6);
        assertThat(meterRegistry.get("ingest.announcement.requests")
                .tags("source", "LH_ANNOUNCEMENT_DETAIL", "mode", "scheduled", "result", "refresh")
                .counter().count()).isEqualTo(4);
        verify(externalRepository, org.mockito.Mockito.times(2)).detail(any());
    }

    @Test
    void 수집_비용과_함께_볼_JVM과_DB풀_계측이_등록된다() {
        assertThat(meterRegistry.get("jvm.memory.used").tag("area", "heap").gauges()).isNotEmpty();
        assertThat(meterRegistry.get("jvm.gc.memory.allocated").counter().count()).isGreaterThanOrEqualTo(0);
        assertThat(meterRegistry.get("hikaricp.connections.pending").gauges()).isNotEmpty();
        assertThat(meterRegistry.get("hikaricp.connections.acquire").timers())
                .anySatisfy(timer -> assertThat(timer.count()).isPositive());
    }

    private List<LhAnnouncementDetailSource> currentDetails() {
        return querySources.findAll().stream()
                .filter(source -> source.getSource() == CollectionSource.LH_ANNOUNCEMENT_DETAIL)
                .flatMap(source -> detailReader.findAllByPanIdAndRequestHashOrderBySourceOrderAsc(
                        source.getPanId(), source.getRequestHash()).stream()).toList();
    }

    private MyHomeAnnouncementSource source(String id, String panId) {
        String payload = """
                {"pblancId":"%s","houseSn":1,"suplyInsttNm":"한국토지주택공사","suplyTyNm":"행복주택",
                 "url":"https://apply.lh.or.kr/panDetail?panId=%s&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=06"}
                """.formatted(id, panId);
        return MyHomeAnnouncementSource.from(0, JsonMapper.builder().build()
                .readValue(payload, MyHomeAnnouncementSourceSnapshot.class));
    }

    private JsonNode response() {
        String payload = """
                [{"resHeader":[{"SS_CODE":"Y","RS_DTTM":"20260917120000"}],
                  "dsSbd":[{"LCC_NT_NM":"테스트 단지"}]}]
                """;
        return JsonMapper.builder().build().readTree(payload);
    }
}
