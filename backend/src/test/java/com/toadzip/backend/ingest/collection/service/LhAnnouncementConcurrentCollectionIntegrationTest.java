package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.ExternalDataCollectionFailureRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionCheckpointRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementDetailSourceRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementSourceRepository;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
class LhAnnouncementConcurrentCollectionIntegrationTest {

    @Autowired
    private LhAnnouncementExternalCollectionService service;

    @Autowired
    private MyHomeAnnouncementSourceRepository sources;

    @Autowired
    private LhAnnouncementDetailSourceRepository details;

    @Autowired
    private LhAnnouncementCollectionCheckpointRepository checkpoints;

    @Autowired
    private LhAnnouncementCollectionLinkRepository links;

    @Autowired
    private ExternalDataCollectionFailureRepository failures;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private LhAnnouncementExternalRepository externalRepository;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        links.deleteAllInBatch();
        checkpoints.deleteAllInBatch();
        details.deleteAllInBatch();
        failures.deleteAllInBatch();
        sources.deleteAllInBatch();
    }

    @Test
    void 병렬_수집은_독립_트랜잭션으로_성공_연결을_저장하고_실패_요청만_재실행한다() {
        sources.saveAll(List.of(source("a", "100"), source("b", "200"), source("c", "100")));
        CountDownLatch started = new CountDownLatch(2);
        when(externalRepository.fetchDetail(any())).thenAnswer(invocation -> {
            LhAnnouncementRequest request = invocation.getArgument(0);
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
        assertThat(details.findAll()).extracting(detail -> detail.getPanId()).containsExactly("100");
        assertThat(checkpoints.count()).isOne();
        assertThat(links.findAll()).extracting(link -> link.getSourceAnnouncementKey())
                .containsExactlyInAnyOrder("a", "c");
        assertThat(failures.count()).isOne();

        clearInvocations(externalRepository);
        doReturn(response()).when(externalRepository).fetchDetail(any());
        var retry = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        assertThat(retry.externalApiCallCount()).isOne();
        assertThat(retry.failedRequestCount()).isZero();
        assertThat(details.findAll()).extracting(detail -> detail.getPanId())
                .containsExactlyInAnyOrder("100", "200");
        assertThat(checkpoints.count()).isEqualTo(2);
        assertThat(links.count()).isEqualTo(3);
        assertThat(meterRegistry.get("ingest.announcement.store")
                .tag("source", "LH_ANNOUNCEMENT_DETAIL").timer().count()).isEqualTo(2);
        assertThat(meterRegistry.get("ingest.external.request")
                .tags("source", "LH_ANNOUNCEMENT_DETAIL", "result", "completed").timer().count()).isEqualTo(2);
        assertThat(meterRegistry.get("ingest.external.request")
                .tags("source", "LH_ANNOUNCEMENT_DETAIL", "result", "failed").timer().count()).isOne();
        assertThat(meterRegistry.get("ingest.announcement.source.rows")
                .tags("source", "LH_ANNOUNCEMENT_DETAIL", "mode", "scheduled", "result", "read")
                .counter().count()).isEqualTo(6);
        assertThat(meterRegistry.get("ingest.announcement.candidates")
                .tags("source", "LH_ANNOUNCEMENT_DETAIL", "mode", "scheduled", "result", "ttl_fresh")
                .counter().count()).isEqualTo(2);
        assertThat(meterRegistry.get("ingest.announcement.requests")
                .tags("source", "LH_ANNOUNCEMENT_DETAIL", "mode", "scheduled", "result", "refresh")
                .counter().count()).isEqualTo(3);
        assertThat(meterRegistry.get("ingest.announcement.requests")
                .tags("source", "LH_ANNOUNCEMENT_DETAIL", "mode", "scheduled", "result", "ttl_fresh")
                .counter().count()).isOne();
        verify(externalRepository).fetchDetail(any());
    }

    @Test
    void 수집_비용과_함께_볼_JVM과_DB풀_계측이_등록된다() {
        assertThat(meterRegistry.get("jvm.memory.used").tag("area", "heap").gauges()).isNotEmpty();
        assertThat(meterRegistry.get("jvm.gc.memory.allocated").counter().count()).isGreaterThanOrEqualTo(0);
        assertThat(meterRegistry.get("hikaricp.connections.pending").gauges()).isNotEmpty();
        assertThat(meterRegistry.get("hikaricp.connections.acquire").timers())
                .anySatisfy(timer -> assertThat(timer.count()).isPositive());
    }

    private MyHomeAnnouncementSource source(String id, String panId) {
        String payload = """
                {"pblancId":"%s","houseSn":1,"suplyInsttNm":"한국토지주택공사","suplyTyNm":"행복주택",
                 "url":"https://apply.lh.or.kr/panDetail?panId=%s&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=06"}
                """.formatted(id, panId);
        return MyHomeAnnouncementSource.from(0, JsonMapper.builder().build()
                .readValue(payload, MyHomeAnnouncementSourceSnapshot.class));
    }

    private ExternalDataResponse response() {
        String payload = """
                [{"resHeader":[{"SS_CODE":"Y","RS_DTTM":"20260917120000"}],
                  "dsSbd":[{"LCC_NT_NM":"테스트 단지"}]}]
                """;
        return new ExternalDataResponse(payload, JsonMapper.builder().build().readTree(payload));
    }
}
