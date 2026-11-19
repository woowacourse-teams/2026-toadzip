package com.toadzip.backend.ingest.lh.refinement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.fixture.dto.LhAnnouncementCatalogPage.Entry;
import com.toadzip.backend.ingest.collection.fixture.repository.LhCatalogStorageFixtures;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.lh.collection.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.lh.collection.repository.LhAnnouncementQueryApiRepository;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementExternalCollectionService;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineCollectionScope;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 증분 수집은 정제까지 마친 뒤 공고목록 행이 바뀐 공고와 아직 마치지 못한 공고만 받는다. */
@ExtendWith(IngestExecutionExtension.class)
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@ActiveProfiles("test")
class LhIncrementalCollectionIntegrationTest {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired private LhAnnouncementExternalCollectionService service;
    @Autowired private LhCatalogStorageFixtures catalogStore;
    @Autowired private JdbcClient jdbc;
    @Autowired private LhAnnouncementRefinementService refinement;
    @MockitoBean private LhAnnouncementQueryApiRepository externalRepository;
    @MockitoSpyBean private LhAnnouncementRefiner refiner;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE lh_announcement_catalog_entries, lh_announcement_query_sources,
                    lh_announcement_collection_links, pending_collection_targets, announcements CASCADE
                """).update();
        when(externalRepository.supply(any())).thenReturn(json("""
                [{"resHeader":[{"SS_CODE":"Y"}]},
                 {"dsList01":[{"SBD_LGO_NM":"단지","HTY_NNA":"59A","DDO_AR":"59.9800","SPL_AR":"80.0000",
                   "HSH_CNT":"100","NOW_HSH_CNT":"10","LS_GMY":"30000000","MM_RFE":"200000"}]}]
                """));
        when(externalRepository.detail(any())).thenReturn(detailResponse());
    }

    @Test
    void 증분_수집은_정제까지_마친_뒤_목록_행이_바뀌지_않은_공고를_다시_받지_않는다() {
        catalogStore.store(List.of(entry("P-1", "공고중"), entry("P-2", "공고중")));
        collectAndRefine(DataPipelineCollectionScope.INCREMENTAL);
        verify(externalRepository, times(2)).supply(any());
        verify(externalRepository, times(2)).detail(any());

        clearInvocations(externalRepository);
        catalogStore.store(List.of(entry("P-1", "공고중"), entry("P-2", "공고중")));
        collectAndRefine(DataPipelineCollectionScope.INCREMENTAL);

        verify(externalRepository, never()).supply(any());
        verify(externalRepository, never()).detail(any());
    }

    @Test
    void 증분_수집은_목록_행이_바뀐_공고만_다시_받는다() {
        catalogStore.store(List.of(entry("P-1", "공고중"), entry("P-2", "공고중")));
        collectAndRefine(DataPipelineCollectionScope.INCREMENTAL);

        clearInvocations(externalRepository);
        catalogStore.store(List.of(entry("P-1", "공고중"), entry("P-2", "정정공고중")));
        collectAndRefine(DataPipelineCollectionScope.INCREMENTAL);

        verify(externalRepository, times(1)).supply(any());
        verify(externalRepository).supply(argThatPanId("P-2"));
        verify(externalRepository, times(1)).detail(any());
        verify(externalRepository).detail(argThatPanId("P-2"));
    }

    @Test
    void 정제가_실패한_공고는_목록_행이_그대로여도_다음_증분_수집이_다시_받는다() {
        catalogStore.store(List.of(entry("P-1", "공고중"), entry("P-2", "공고중")));
        doThrow(new IllegalStateException("정제 실패")).when(refiner)
                .refine(argThat(target -> target != null && "P-1".equals(target.getPanId())), any(), any());
        collectAndRefine(DataPipelineCollectionScope.INCREMENTAL);

        clearInvocations(externalRepository);
        reset(refiner);
        catalogStore.store(List.of(entry("P-1", "공고중"), entry("P-2", "공고중")));
        collectAndRefine(DataPipelineCollectionScope.INCREMENTAL);

        verify(externalRepository, times(1)).supply(any());
        verify(externalRepository).supply(argThatPanId("P-1"));
        verify(externalRepository).detail(argThatPanId("P-1"));
    }

    @Test
    void 상세가_덜_채워져_공고를_만들지_못한_공고는_목록_행이_그대로여도_다음_증분_수집이_다시_받는다() {
        catalogStore.store(List.of(entry("P-1", "공고중"), entry("P-2", "공고중")));
        when(externalRepository.detail(argThatPanId("P-1"))).thenReturn(detailResponseWithoutWinnerDate());
        collectAndRefine(DataPipelineCollectionScope.INCREMENTAL);
        assertThat(announcementCount("P-1")).isZero();

        clearInvocations(externalRepository);
        when(externalRepository.detail(argThatPanId("P-1"))).thenReturn(detailResponse());
        catalogStore.store(List.of(entry("P-1", "공고중"), entry("P-2", "공고중")));
        collectAndRefine(DataPipelineCollectionScope.INCREMENTAL);

        verify(externalRepository, times(1)).detail(any());
        verify(externalRepository).detail(argThatPanId("P-1"));
        assertThat(announcementCount("P-1")).isOne();
    }

    @Test
    void 받다_실패한_공고는_목록_행이_그대로여도_다음_증분_수집이_다시_받는다() {
        catalogStore.store(List.of(entry("P-1", "공고중"), entry("P-2", "공고중")));
        when(externalRepository.detail(argThatPanId("P-2"))).thenThrow(new ExternalDataRequestException("상세 응답 실패"));
        collectAndRefine(DataPipelineCollectionScope.INCREMENTAL);

        clearInvocations(externalRepository);
        when(externalRepository.detail(argThatPanId("P-2"))).thenReturn(detailResponse());
        catalogStore.store(List.of(entry("P-1", "공고중"), entry("P-2", "공고중")));
        collectAndRefine(DataPipelineCollectionScope.INCREMENTAL);

        verify(externalRepository, times(1)).detail(any());
        verify(externalRepository).detail(argThatPanId("P-2"));
    }

    @Test
    void 수집만_하고_정제하지_않은_공고는_다음_증분_수집이_다시_받는다() {
        catalogStore.store(List.of(entry("P-1", "공고중"), entry("P-2", "공고중")));
        inScope(DataPipelineCollectionScope.INCREMENTAL, () -> service.collectBundles(candidates -> {
        }));

        clearInvocations(externalRepository);
        catalogStore.store(List.of(entry("P-1", "공고중"), entry("P-2", "공고중")));
        collectAndRefine(DataPipelineCollectionScope.INCREMENTAL);

        verify(externalRepository, times(2)).supply(any());
        verify(externalRepository, times(2)).detail(any());
    }

    @Test
    void 전체_수집은_목록_행이_바뀌지_않은_공고도_다시_받고_정제를_마쳤다고_남긴다() {
        catalogStore.store(List.of(entry("P-1", "공고중"), entry("P-2", "공고중")));
        collectAndRefine(DataPipelineCollectionScope.INCREMENTAL);

        clearInvocations(externalRepository);
        catalogStore.store(List.of(entry("P-1", "공고중"), entry("P-2", "공고중")));
        collectAndRefine(DataPipelineCollectionScope.FULL);

        verify(externalRepository, times(2)).supply(any());
        verify(externalRepository, times(2)).detail(any());

        clearInvocations(externalRepository);
        catalogStore.store(List.of(entry("P-1", "공고중"), entry("P-2", "공고중")));
        collectAndRefine(DataPipelineCollectionScope.INCREMENTAL);

        verify(externalRepository, never()).supply(any());
    }

    /** 자동 실행의 LH 공고 갱신처럼 묶음을 받을 때마다 정제한다. 받다 실패한 묶음은 실행을 멈추지 않고 대기로 남는다. */
    private void collectAndRefine(DataPipelineCollectionScope scope) {
        inScope(scope, () -> {
            var run = refinement.begin();
            service.collectBundles(run::refineBundle);
        });
    }

    private void inScope(DataPipelineCollectionScope scope, Runnable work) {
        var lease = IngestExecutionScope.current().orElseThrow();
        try (var ignored = IngestExecutionScope.open(UUID.randomUUID(), lease, null, scope)) {
            work.run();
        }
    }

    private int announcementCount(String panId) {
        return jdbc.sql("SELECT count(*) FROM announcements WHERE lh_pan_id = ?").param(panId).query(Integer.class)
                .single();
    }

    private static LhAnnouncementQuery argThatPanId(String panId) {
        ArgumentMatcher<LhAnnouncementQuery> matcher = request -> request != null && panId.equals(request.panId());
        return argThat(matcher);
    }

    private Entry entry(String panId, String status) {
        return new Entry(new LhAnnouncementCatalogSnapshot(panId, "03", "06", "10", "063", panId + " 행복주택 입주자 모집",
                status, TODAY.format(DATE), TODAY.format(DATE), TODAY.plusDays(10).format(DATE),
                "https://apply.lh.or.kr/" + panId, ""), "{}");
    }

    private JsonNode detailResponse() {
        return detailResponse(",\"PZWR_ANC_DT\":\"20261210\"");
    }

    /** 당첨자 발표일이 아직 없는 상세: 새 공고를 만들지 못한다. */
    private JsonNode detailResponseWithoutWinnerDate() {
        return detailResponse("");
    }

    private JsonNode detailResponse(String winnerDateField) {
        return json("""
                [{"resHeader":[{"SS_CODE":"Y"}]},
                 {"dsSbd":[{"LCC_NT_NM":"단지","LGDN_ADR":"경기도 화성시 동탄대로 900","HSH_CNT":"100"}]},
                 {"dsSplScdl":[{"SBSC_ACP_ST_DT":"20261201","SBSC_ACP_CLSG_DT":"20261203"%s}]}]
                """.formatted(winnerDateField));
    }

    private JsonNode json(String payload) {
        return JsonMapper.builder().build().readTree(payload);
    }
}
