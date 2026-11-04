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
                "LH 공고 목록 수집", "LH 공고 공급 원본 수집", "LH 공고 상세 원본 수집", "LH 공고 정제");
        assertThat(announcements.findAllByLhPanIdOrderByIdAsc("P-LH-ONLY")).singleElement().satisfies(announcement ->
                assertThat(announcement.getSourceAnnouncementIdentifier()).isEqualTo("LH:P-LH-ONLY"));
        assertThat(count("SELECT COUNT(*) FROM myhome_announcement_source_rows")).isEqualTo(myHomeRows);
        assertThat(count("SELECT COUNT(*) FROM source_collection_records WHERE source = 'MYHOME_ANNOUNCEMENT'"))
                .isEqualTo(myHomeRecords);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private tools.jackson.databind.JsonNode json(String payload) {
        return JsonMapper.builder().build().readTree(payload);
    }
}
