package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionCheckpoint;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionLink;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.ExternalDataCollectionFailureRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCatalogSourceRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionCheckpointRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionLinkRepository;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementSourceRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
class LhAnnouncementCollectionQueryCountTest {

    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
    private static final ExternalDataSource DETAIL = ExternalDataSource.LH_ANNOUNCEMENT_DETAIL;

    @Autowired
    private LhAnnouncementExternalCollectionService service;

    @Autowired
    private MyHomeAnnouncementSourceRepository sources;

    @Autowired
    private LhAnnouncementCollectionLinkRepository links;

    @Autowired
    private LhAnnouncementCollectionCheckpointRepository checkpoints;

    @Autowired
    private ExternalDataCollectionFailureRepository failures;

    @Autowired
    private LhAnnouncementCatalogSourceRepository catalog;

    @Autowired
    private EntityManagerFactory factory;

    @MockitoBean
    private LhAnnouncementExternalRepository external;

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
        failures.deleteAllInBatch();
        links.deleteAllInBatch();
        checkpoints.deleteAllInBatch();
        catalog.deleteAllInBatch();
        sources.deleteAllInBatch();
    }

    @ParameterizedTest
    @CsvSource({"32,true", "256,true", "501,true", "32,false", "501,false"})
    void 정상_캐시_공고의_실패_이력은_배치마다_한_번만_조회한다(int count, boolean sharedRequest) {
        prepareCache(count, sharedRequest);
        Statistics stats = factory.unwrap(SessionFactory.class).getStatistics();
        boolean previouslyEnabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        stats.clear();
        try {
            var result = service.collect(DETAIL);
            long failureQueries = failureQueryCount(stats);
            assertThat(result.failedRequestCount()).isZero();
            assertThat(result.externalApiCallCount()).isZero();
            verifyNoInteractions(external);
            long batchCount = (count + 499L) / 500;
            assertThat(failureQueries).as("공고 %s개, 전체 SQL %s회", count, stats.getPrepareStatementCount())
                    .isEqualTo(batchCount);
        }
        finally {
            stats.setStatisticsEnabled(previouslyEnabled);
        }
    }
    private long failureQueryCount(Statistics statistics) {
        long queryCount = 0;
        for (String query : statistics.getQueries()) {
            if (query.contains("ExternalDataCollectionFailure")) {
                queryCount += statistics.getQueryStatistics(query).getExecutionCount();
            }
        }
        return queryCount;
    }

    private void prepareCache(int count, boolean sharedRequest) {
        List<MyHomeAnnouncementSource> rows = new ArrayList<>();
        List<LhAnnouncementCollectionLink> completedLinks = new ArrayList<>();
        Map<String, LhAnnouncementCollectionCheckpoint> completedRequests = new LinkedHashMap<>();
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
                    LhAnnouncementCollectionCheckpoint.complete(DETAIL, identifier, request, panId, NOW));
        }
        sources.saveAll(rows);
        links.saveAll(completedLinks);
        checkpoints.saveAll(completedRequests.values());
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
