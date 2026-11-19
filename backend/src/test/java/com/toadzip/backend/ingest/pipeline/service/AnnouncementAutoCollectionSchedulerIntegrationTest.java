package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.fixture.dto.LhAnnouncementCatalogPage.Entry;
import com.toadzip.backend.ingest.collection.fixture.repository.LhCatalogStorageFixtures;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.service.LhAnnouncementCatalogCollectionService;
import com.toadzip.backend.ingest.lh.collection.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.lh.collection.repository.LhAnnouncementQueryApiRepository;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineCollectionScope;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineFailureCause;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "ingest.service-key=test-service-key",
        "ingest.auto-collection.enabled=true",
        "ingest.auto-collection.initial-delay=PT24H",
        "ingest.auto-collection.interval=PT1H"
})
@ActiveProfiles("test")
class AnnouncementAutoCollectionSchedulerIntegrationTest {

    private static final DateTimeFormatter CATALOG_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    @Autowired private AnnouncementAutoCollectionScheduler scheduler;
    @Autowired private DataPipelineExecutionRepository executions;
    @Autowired private DataPipelineExecutionLock executionLock;
    @Autowired private LhCatalogStorageFixtures catalog;
    @Autowired private MeterRegistry meters;
    @Autowired private JdbcClient jdbc;
    @MockitoBean(name = "dataPipelineExecutor") private Executor executor;
    @MockitoBean private LhAnnouncementCatalogCollectionService catalogCollection;
    @MockitoBean private LhAnnouncementQueryApiRepository lhApi;

    private final AtomicReference<Runnable> task = new AtomicReference<>();
    private final Set<String> suppliedPanIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE data_pipeline_executions, announcements, lh_announcement_catalog_entries,
                    lh_announcement_query_sources, pending_collection_targets, admin_data_changes,
                    notification_outbox_events CASCADE
                """).update();
        doAnswer(invocation -> {
            task.set(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        when(catalogCollection.collect()).thenAnswer(invocation -> {
            catalog.store(List.of(entry("P-OPEN", today.plusDays(10)), entry("P-JUST-CLOSED", today.minusDays(2)),
                    entry("P-ENDED", today.minusDays(10))));
            return new ExternalDataCollectionReport("lh-announcement-catalog", 3, 0, 3);
        });
        when(lhApi.supply(any())).thenAnswer(invocation -> {
            suppliedPanIds.add(invocation.<LhAnnouncementQuery>getArgument(0).panId());
            return json("""
                    [{"resHeader":[{"SS_CODE":"Y"}]},{"dsList01":[{"SBD_LGO_NM":"LH 단지","HTY_NNA":"59A",
                      "DDO_AR":"59.9800","SPL_AR":"80.0000","HSH_CNT":"100","NOW_HSH_CNT":"10"}]}]
                    """);
        });
        when(lhApi.detail(any())).thenReturn(json("""
                [{"resHeader":[{"SS_CODE":"Y"}]},{"dsSbd":[{"LCC_NT_NM":"LH 단지","HSH_CNT":"100"}]}]
                """));
    }

    @Test
    void 한_시간이_지난_LH를_자동으로_시작하고_주_1회_전체_수집을_마쳤으면_막_마감된_공고까지만_받는다() {
        finished(DataPipelineType.ANNOUNCEMENT_SYNC, DataPipelineExecutionStatus.COMPLETED, Duration.ofHours(2));
        recentlySucceededBoards();

        scheduler.startDue();

        DataPipelineExecution started = latest(DataPipelineType.ANNOUNCEMENT_SYNC);
        assertThat(started.getExecutionTrigger().name()).isEqualTo("SCHEDULED");
        assertThat(started.getScheduledAt()).isNotNull();
        assertThat(started.getCollectionScope().name()).isEqualTo("INCREMENTAL");
        task.get().run();
        assertThat(suppliedPanIds).containsExactlyInAnyOrder("P-OPEN", "P-JUST-CLOSED");
        assertThat(latest(DataPipelineType.ANNOUNCEMENT_SYNC).getStatus().isSuccessful()).isTrue();
    }

    @Test
    void 전체_수집에서_일주일이_지났으면_LH를_전체로_받아_마감_뒤_한_달_안의_공고도_받는다() {
        finished(DataPipelineType.ANNOUNCEMENT_SYNC, DataPipelineExecutionStatus.COMPLETED, Duration.ofDays(8));
        recentlySucceededBoards();

        scheduler.startDue();

        assertThat(latest(DataPipelineType.ANNOUNCEMENT_SYNC).getCollectionScope().name()).isEqualTo("FULL");
        task.get().run();
        assertThat(suppliedPanIds).containsExactlyInAnyOrder("P-OPEN", "P-JUST-CLOSED", "P-ENDED");
    }

    @Test
    void LH_공고_일부만_정제에_실패한_실행도_성공으로_세어_연속_실패로_올리지_않고_전체_수집으로도_센다() {
        Instant partialAt = finished(DataPipelineType.ANNOUNCEMENT_SYNC, DataPipelineExecutionStatus.FAILED,
                DataPipelineFailureCause.PARTIAL, Duration.ofHours(2));
        recentlySucceededBoards();

        scheduler.startDue();

        assertThat(latest(DataPipelineType.ANNOUNCEMENT_SYNC).getCollectionScope().name()).isEqualTo("INCREMENTAL");
        assertThat(meters.get("toadzip.ingest.auto.last.success").tag("type", "ANNOUNCEMENT_SYNC")
                .gauge().value()).isEqualTo(partialAt.getEpochSecond());
        assertThat(meters.get("toadzip.ingest.auto.failure.streak").tag("type", "ANNOUNCEMENT_SYNC")
                .gauge().value()).isZero();
        task.get().run();
    }

    @Test
    void 대상이_모두_실패한_실행은_성공으로_세지_않아_연속_실패로_올리고_다음_실행을_미룬다() {
        Instant succeededAt = finished(DataPipelineType.ANNOUNCEMENT_SYNC, DataPipelineExecutionStatus.COMPLETED,
                Duration.ofHours(3));
        finished(DataPipelineType.ANNOUNCEMENT_SYNC, DataPipelineExecutionStatus.FAILED,
                DataPipelineFailureCause.ALL_TARGETS_FAILED, Duration.ofMinutes(5));
        recentlySucceededBoards();
        long before = executions.count();

        scheduler.startDue();

        assertThat(executions.count()).isEqualTo(before);
        assertThat(meters.get("toadzip.ingest.auto.last.success").tag("type", "ANNOUNCEMENT_SYNC")
                .gauge().value()).isEqualTo(succeededAt.getEpochSecond());
        assertThat(meters.get("toadzip.ingest.auto.failure.streak").tag("type", "ANNOUNCEMENT_SYNC")
                .gauge().value()).isOne();
    }

    @Test
    void 증분_수집만_일부_실패로_끝났으면_전체_수집으로_세지_않아_다음_실행을_전체로_받는다() {
        finished(DataPipelineType.ANNOUNCEMENT_SYNC, DataPipelineExecutionStatus.FAILED,
                DataPipelineFailureCause.PARTIAL, DataPipelineCollectionScope.INCREMENTAL, Duration.ofHours(2));
        recentlySucceededBoards();

        scheduler.startDue();

        assertThat(latest(DataPipelineType.ANNOUNCEMENT_SYNC).getCollectionScope().name()).isEqualTo("FULL");
        task.get().run();
    }

    @Test
    void 관리자_실행이_잠금을_잡고_있으면_자동_실행은_건너뛰고_실행_기록을_만들지_않는다() {
        try (var ignored = executionLock.tryAcquire(UUID.randomUUID()).orElseThrow()) {
            scheduler.startDue();
        }

        assertThat(executions.count()).isZero();
    }

    @Test
    void 확인할_때마다_유형별_마지막_성공_시각과_연속_실패_수를_지표로_내보낸다() {
        Instant succeededAt = finished(DataPipelineType.SH_ANNOUNCEMENT_COLLECTION,
                DataPipelineExecutionStatus.COMPLETED, Duration.ofMinutes(30));
        finished(DataPipelineType.SH_ANNOUNCEMENT_COLLECTION, DataPipelineExecutionStatus.FAILED, Duration.ofMinutes(20));
        finished(DataPipelineType.SH_ANNOUNCEMENT_COLLECTION, DataPipelineExecutionStatus.FAILED, Duration.ofMinutes(5));

        try (var ignored = executionLock.tryAcquire(UUID.randomUUID()).orElseThrow()) {
            scheduler.startDue();
        }

        assertThat(meters.get("toadzip.ingest.auto.last.success").tag("type", "SH_ANNOUNCEMENT_COLLECTION")
                .gauge().value()).isEqualTo(succeededAt.getEpochSecond());
        assertThat(meters.get("toadzip.ingest.auto.failure.streak").tag("type", "SH_ANNOUNCEMENT_COLLECTION")
                .gauge().value()).isEqualTo(2);
        assertThat(meters.get("toadzip.ingest.auto.last.success").tag("type", "GH_ANNOUNCEMENT_COLLECTION")
                .gauge().value()).isNaN();
    }

    private void recentlySucceededBoards() {
        finished(DataPipelineType.SH_ANNOUNCEMENT_COLLECTION, DataPipelineExecutionStatus.COMPLETED, Duration.ofMinutes(10));
        finished(DataPipelineType.GH_ANNOUNCEMENT_COLLECTION, DataPipelineExecutionStatus.COMPLETED, Duration.ofMinutes(10));
    }

    private Instant finished(DataPipelineType type, DataPipelineExecutionStatus status, Duration ago) {
        return finished(type, status, null, ago);
    }

    private Instant finished(DataPipelineType type, DataPipelineExecutionStatus status,
            DataPipelineFailureCause cause, Duration ago) {
        return finished(type, status, cause, null, ago);
    }

    private Instant finished(DataPipelineType type, DataPipelineExecutionStatus status,
            DataPipelineFailureCause cause, DataPipelineCollectionScope scope, Duration ago) {
        Instant startedAt = Instant.now().minus(ago);
        UUID executionId = UUID.randomUUID();
        executions.saveAndFlush(DataPipelineExecution.start(executionId, type, startedAt));
        jdbc.sql("UPDATE data_pipeline_executions SET status = :status, failure_cause = :cause, "
                        + "collection_scope = :scope, finished_at = :finishedAt WHERE execution_id = :executionId")
                .param("status", status.name())
                .param("cause", cause == null ? null : cause.name())
                .param("scope", scope == null ? null : scope.name())
                .param("finishedAt", Timestamp.from(startedAt.plusSeconds(60)))
                .param("executionId", executionId)
                .update();
        return startedAt;
    }

    private DataPipelineExecution latest(DataPipelineType type) {
        return executions.findFirstByTypeOrderByIdDesc(type).orElseThrow();
    }

    private Entry entry(String panId, LocalDate closing) {
        return new Entry(new LhAnnouncementCatalogSnapshot(panId, "03", "06", "10", "063", panId + " 행복주택 입주자 모집",
                "공고중", closing.minusDays(20).format(CATALOG_DATE), closing.minusDays(20).format(CATALOG_DATE),
                closing.format(CATALOG_DATE), "https://apply.lh.or.kr/" + panId, ""), "{}");
    }

    private JsonNode json(String payload) {
        return JsonMapper.builder().build().readTree(payload);
    }
}
