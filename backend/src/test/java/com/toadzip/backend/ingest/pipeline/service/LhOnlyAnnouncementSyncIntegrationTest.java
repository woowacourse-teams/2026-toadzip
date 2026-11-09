package com.toadzip.backend.ingest.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.fixture.dto.LhAnnouncementCatalogPage.Entry;
import com.toadzip.backend.ingest.collection.fixture.repository.LhCatalogStorageFixtures;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.service.LhAnnouncementCatalogCollectionService;
import com.toadzip.backend.ingest.lh.collection.repository.LhAnnouncementQueryApiRepository;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecutionStatus;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.repository.LhAnnouncementCatalogSourceReader;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "ingest.service-key=test-service-key")
@ActiveProfiles("test")
class LhOnlyAnnouncementSyncIntegrationTest {

    private static final DateTimeFormatter CATALOG_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired private DataPipelineExecutionService service;
    @Autowired private DataPipelineExecutionQueryService query;
    @Autowired private LhCatalogStorageFixtures catalog;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private JdbcClient jdbc;
    @MockitoBean(name = "dataPipelineExecutor") private Executor executor;
    @MockitoBean private LhAnnouncementCatalogCollectionService catalogCollection;
    @MockitoBean private LhAnnouncementQueryApiRepository lhApi;
    @MockitoSpyBean private LhAnnouncementCatalogSourceReader catalogReader;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE announcements, lh_announcement_catalog_entries, lh_announcement_query_sources,
                    admin_data_changes, notification_outbox_events CASCADE
                """).update();
    }

    @Test
    void 마이홈_원천이_없는_LH_공고도_공고_갱신에서_수집하고_정제하며_마이홈_공고는_수집하지_않는다() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        catalog.store(List.of(new Entry(new LhAnnouncementCatalogSnapshot("P-LH-ONLY", "03", "06", "10", "063",
                "LH 전용 행복주택 입주자 모집", "공고중", today.format(CATALOG_DATE), today.format(CATALOG_DATE),
                today.plusDays(10).format(CATALOG_DATE), "https://apply.lh.or.kr/P-LH-ONLY", ""), "{}")));
        when(catalogCollection.collect()).thenReturn(new ExternalDataCollectionReport("lh-announcement-catalog", 1, 0, 1));
        when(lhApi.supply(any())).thenReturn(json("""
                [{"resHeader":[{"SS_CODE":"Y"}]},
                 {"dsList01":[{"SBD_LGO_NM":"LH 전용 단지","HTY_NNA":"59A","DDO_AR":"59.9800","SPL_AR":"80.0000",
                   "HSH_CNT":"100","NOW_HSH_CNT":"10","LS_GMY":"30000000","MM_RFE":"200000"}]}]
                """));
        when(lhApi.detail(any())).thenReturn(json("""
                [{"resHeader":[{"SS_CODE":"Y"}]},
                 {"dsSbd":[{"LCC_NT_NM":"LH 전용 단지","LGDN_ADR":"경기도 화성시 동탄대로 900","HSH_CNT":"100"}]},
                 {"dsSplScdl":[{"SBD_LGO_NM":"LH 전용 단지","SBSC_ACP_ST_DT":"%s","SBSC_ACP_CLSG_DT":"%s",
                   "PZWR_ANC_DT":"%s"}]}]
                """.formatted(today.plusDays(1).format(COMPACT_DATE), today.plusDays(3).format(COMPACT_DATE),
                today.plusDays(20).format(COMPACT_DATE))));
        long myHomeRows = count("SELECT COUNT(*) FROM myhome_announcement_source_rows");
        long myHomeRecords = count("SELECT COUNT(*) FROM source_collection_records WHERE source = 'MYHOME_ANNOUNCEMENT'");
        AtomicReference<Runnable> task = new AtomicReference<>();
        doAnswer(invocation -> {
            task.set(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any());

        var accepted = service.start(DataPipelineType.ANNOUNCEMENT_SYNC);
        task.get().run();

        var result = query.find(accepted.executionId());
        assertThat(result.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED);
        assertThat(result.completedSteps()).containsExactly(
                "LH 공고 목록 수집", "LH 공고 수집·정제");
        assertThat(announcements.findAllByLhPanIdOrderByIdAsc("P-LH-ONLY")).singleElement().satisfies(announcement ->
                assertThat(announcement.getSourceAnnouncementIdentifier()).isEqualTo("LH:P-LH-ONLY"));
        assertThat(count("SELECT COUNT(*) FROM myhome_announcement_source_rows")).isEqualTo(myHomeRows);
        assertThat(count("SELECT COUNT(*) FROM source_collection_records WHERE source = 'MYHOME_ANNOUNCEMENT'"))
                .isEqualTo(myHomeRecords);
    }

    @Test
    void 한_묶음의_공급이_호출_제한이면_그_묶음은_정제하지_않고_앞서_끝난_묶음만_정제한다() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        // 목록 수집은 실행 시작 뒤에 일어나므로 공고목록의 변경 시각은 이번 실행 안이다.
        when(catalogCollection.collect()).thenAnswer(invocation -> {
            catalog.store(List.of(entry("P-A", "A 행복주택 입주자 모집", today), entry("P-B", "B 행복주택 입주자 모집", today)));
            return new ExternalDataCollectionReport("lh-announcement-catalog", 2, 0, 2);
        });
        when(lhApi.supply(any())).thenAnswer(invocation -> {
            if (invocation.<com.toadzip.backend.ingest.lh.collection.domain.LhAnnouncementQuery>getArgument(0)
                    .panId().equals("P-B")) {
                throw new com.toadzip.backend.ingest.lh.collection.repository.external.LhAnnouncementUnavailableException(
                        "호출 제한", true);
            }
            return supplyJson();
        });
        when(lhApi.detail(any())).thenReturn(detailJson(today));
        AtomicReference<Runnable> task = new AtomicReference<>();
        doAnswer(invocation -> {
            task.set(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any());

        var accepted = service.start(DataPipelineType.ANNOUNCEMENT_SYNC);
        task.get().run();

        var result = query.find(accepted.executionId());
        assertThat(result.status()).isEqualTo(DataPipelineExecutionStatus.COMPLETED_WITH_SKIPS);
        // 공급을 못 받은 묶음은 정제 대상에도 넣지 않는다.
        assertThat(result.skippedSteps().getFirst().serverResponse().toString()).contains("targetCount=1");
        assertThat(announcements.findAllByLhPanIdOrderByIdAsc("P-A")).hasSize(1);
        assertThat(announcements.findAllByLhPanIdOrderByIdAsc("P-B")).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM pending_collection_targets WHERE target_key LIKE '%P-B%'")).isPositive();
    }

    @Test
    void 공급은_받았지만_상세를_못_받은_묶음은_정제하지_않고_대기로_남긴다() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        when(catalogCollection.collect()).thenAnswer(invocation -> {
            catalog.store(List.of(entry("P-A", "A 행복주택 입주자 모집", today), entry("P-B", "B 행복주택 입주자 모집", today)));
            return new ExternalDataCollectionReport("lh-announcement-catalog", 2, 0, 2);
        });
        when(lhApi.supply(any())).thenReturn(supplyJson());
        when(lhApi.detail(any())).thenAnswer(invocation -> {
            if (invocation.<com.toadzip.backend.ingest.lh.collection.domain.LhAnnouncementQuery>getArgument(0)
                    .panId().equals("P-B")) {
                throw new com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException("상세 실패");
            }
            return detailJson(today);
        });
        AtomicReference<Runnable> task = new AtomicReference<>();
        doAnswer(invocation -> {
            task.set(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any());

        var accepted = service.start(DataPipelineType.ANNOUNCEMENT_SYNC);
        task.get().run();

        assertThat(query.find(accepted.executionId()).status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(announcements.findAllByLhPanIdOrderByIdAsc("P-A")).hasSize(1);
        assertThat(announcements.findAllByLhPanIdOrderByIdAsc("P-B")).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM pending_collection_targets WHERE target_key LIKE '%P-B%'")).isPositive();
    }

    @Test
    void 묶음_정제_대상을_읽다_실패해도_수집_보고서와_받은_원천은_보존하고_실패로_기록한다() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        when(catalogCollection.collect()).thenAnswer(invocation -> {
            catalog.store(List.of(entry("P-A", "A 행복주택 입주자 모집", today)));
            return new ExternalDataCollectionReport("lh-announcement-catalog", 1, 0, 1);
        });
        when(lhApi.supply(any())).thenReturn(supplyJson());
        when(lhApi.detail(any())).thenReturn(detailJson(today));
        // 수집 후보를 고를 때의 조회는 그대로 두고, 정제 서비스가 읽을 때만 실패시킨다.
        doAnswer(invocation -> {
            boolean fromRefinement = java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(frame -> frame.getClassName().contains("LhAnnouncementRefinementService"));
            if (fromRefinement) {
                throw new IllegalStateException("조회 실패");
            }
            return invocation.callRealMethod();
        }).when(catalogReader).findAllByPanIdInAndPresentInLatestCatalogTrue(any());
        AtomicReference<Runnable> task = new AtomicReference<>();
        doAnswer(invocation -> {
            task.set(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any());

        var accepted = service.start(DataPipelineType.ANNOUNCEMENT_SYNC);
        task.get().run();

        var result = query.find(accepted.executionId());
        assertThat(result.status()).isEqualTo(DataPipelineExecutionStatus.FAILED);
        assertThat(result.failure().serverResponse()).as(String.valueOf(result.failure())).isNotNull();
        assertThat(result.failure().serverResponse().toString()).contains("lh-announcement-supply", "lh-announcement-detail", "failedAnnouncementCount=1");
        assertThat(count("SELECT COUNT(*) FROM lh_announcement_query_sources")).isPositive();
        assertThat(announcements.findAllByLhPanIdOrderByIdAsc("P-A")).isEmpty();
    }

    @Test
    void 이번_실행에서_목록이_바뀐_종료_정책_밖_공고는_마무리에서_정제한다() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        when(catalogCollection.collect()).thenAnswer(invocation -> {
            catalog.store(List.of(entry("P-A", "A 행복주택 입주자 모집", today),
                    endedEntry("P-OLD", "오래전 끝난 행복주택 입주자 모집", today.minusDays(400))));
            return new ExternalDataCollectionReport("lh-announcement-catalog", 2, 0, 2);
        });
        when(lhApi.supply(any())).thenReturn(supplyJson());
        when(lhApi.detail(any())).thenReturn(detailJson(today));
        AtomicReference<Runnable> task = new AtomicReference<>();
        doAnswer(invocation -> {
            task.set(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any());

        var accepted = service.start(DataPipelineType.ANNOUNCEMENT_SYNC);
        task.get().run();

        var result = query.find(accepted.executionId());
        // 묶음으로 수집한 P-A와, 수집 대상이 아니라 마무리에서 정제한 P-OLD가 정제 대상이다(P-OLD는 원천이 없어 보류).
        assertThat(result.completedStepResults().getLast().report().toString()).contains("targetCount=2");
        assertThat(announcements.findAllByLhPanIdOrderByIdAsc("P-A")).hasSize(1);
    }

    private Entry endedEntry(String panId, String name, LocalDate closing) {
        return new Entry(new LhAnnouncementCatalogSnapshot(panId, "03", "06", "10", "063", name, "접수마감",
                closing.minusDays(10).format(CATALOG_DATE), closing.minusDays(10).format(CATALOG_DATE),
                closing.format(CATALOG_DATE), "https://apply.lh.or.kr/" + panId, ""), "{}");
    }

    private Entry entry(String panId, String name, LocalDate today) {
        return new Entry(new LhAnnouncementCatalogSnapshot(panId, "03", "06", "10", "063", name, "공고중",
                today.format(CATALOG_DATE), today.format(CATALOG_DATE), today.plusDays(10).format(CATALOG_DATE),
                "https://apply.lh.or.kr/" + panId, ""), "{}");
    }

    private tools.jackson.databind.JsonNode supplyJson() {
        return json("""
                [{"resHeader":[{"SS_CODE":"Y"}]},
                 {"dsList01":[{"SBD_LGO_NM":"LH 단지","HTY_NNA":"59A","DDO_AR":"59.9800","SPL_AR":"80.0000",
                   "HSH_CNT":"100","NOW_HSH_CNT":"10","LS_GMY":"30000000","MM_RFE":"200000"}]}]
                """);
    }

    private tools.jackson.databind.JsonNode detailJson(LocalDate today) {
        return json("""
                [{"resHeader":[{"SS_CODE":"Y"}]},
                 {"dsSbd":[{"LCC_NT_NM":"LH 단지","LGDN_ADR":"경기도 화성시 동탄대로 900","HSH_CNT":"100"}]},
                 {"dsSplScdl":[{"SBD_LGO_NM":"LH 단지","SBSC_ACP_ST_DT":"%s","SBSC_ACP_CLSG_DT":"%s",
                   "PZWR_ANC_DT":"%s"}]}]
                """.formatted(today.plusDays(1).format(COMPACT_DATE), today.plusDays(3).format(COMPACT_DATE),
                today.plusDays(20).format(COMPACT_DATE)));
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private tools.jackson.databind.JsonNode json(String payload) {
        return JsonMapper.builder().build().readTree(payload);
    }
}
