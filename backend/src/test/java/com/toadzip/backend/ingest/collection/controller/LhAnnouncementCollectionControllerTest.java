package com.toadzip.backend.ingest.collection.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCatalogCollectionService;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementDetailCollectionService;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementSupplyCollectionService;
import com.toadzip.backend.ingest.collection.service.VerifiedLhSupplyReplacementService;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.pipeline.configuration.DataPipelineExecutionWebConfiguration;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@WebMvcTest(LhAnnouncementCollectionController.class)
@Import(LhAnnouncementCollectionControllerTest.LockWebConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
class LhAnnouncementCollectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LhAnnouncementDetailCollectionService detailCollectionService;

    @MockitoBean
    private LhAnnouncementCatalogCollectionService catalogCollectionService;

    @MockitoBean
    private LhAnnouncementSupplyCollectionService supplyCollectionService;

    @MockitoBean
    private VerifiedLhSupplyReplacementService replacementService;

    @MockitoBean
    private IngestExecutionOwnershipService ownershipService;

    @TestConfiguration
    static class LockWebConfiguration {
        @Bean
        WebMvcConfigurer executionLockConfigurer(IngestExecutionOwnershipService ownershipService) {
            return new DataPipelineExecutionWebConfiguration(ownershipService);
        }
    }

    @Test
    void LH_공고목록만_단독_수집한다() throws Exception {
        when(catalogCollectionService.collect())
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-catalog", 212, 0, 1));

        mockMvc.perform(post("/api/admin/ingest/lh/announcements/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("lh-announcement-catalog"))
                .andExpect(jsonPath("$.storedRowCount").value(212))
                .andExpect(jsonPath("$.externalApiCallCount").value(1));
    }

    @Test
    void LH_공고목록_조회에_실패하면_502를_반환한다() throws Exception {
        when(catalogCollectionService.collect())
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-catalog", 0, 1, 2));

        mockMvc.perform(post("/api/admin/ingest/lh/announcements/catalog"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.storedRowCount").value(0))
                .andExpect(jsonPath("$.failedRequestCount").value(1));
    }

    @Test
    void LH_상세와_공급_원본을_서로_다른_경로에서_수집한다() throws Exception {
        when(detailCollectionService.collect())
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-detail", 1, 0, 1));
        when(supplyCollectionService.collect())
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-supply", 2, 0, 1));

        mockMvc.perform(post("/api/admin/ingest/lh/announcements/details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("lh-announcement-detail"))
                .andExpect(jsonPath("$.storedRowCount").value(1));

        mockMvc.perform(post("/api/admin/ingest/lh/announcements/supplies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("lh-announcement-supply"))
                .andExpect(jsonPath("$.storedRowCount").value(2));
    }

    @Test
    void LH_상세_수집의_중복_실행은_409를_반환한다() throws Exception {
        when(detailCollectionService.collect())
                .thenThrow(new IngestAlreadyRunningException(
                        "lh-announcement-detail 수집이 이미 실행 중입니다."
                ));

        mockMvc.perform(post("/api/admin/ingest/lh/announcements/details"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INGEST_ALREADY_RUNNING"))
                .andExpect(jsonPath("$.message").value("lh-announcement-detail 수집이 이미 실행 중입니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void LH_공급_수집이_실패하면_502와_수집_결과를_반환한다() throws Exception {
        when(supplyCollectionService.collect())
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-supply", 2, 1, 4));

        mockMvc.perform(post("/api/admin/ingest/lh/announcements/supplies"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.storedRowCount").value(2))
                .andExpect(jsonPath("$.failedRequestCount").value(1));
    }

    @Test
    void 공고_식별자로_LH_상세와_공급을_강제_갱신한다() throws Exception {
        when(detailCollectionService.refresh("announcement-100"))
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-detail", 1, 0, 1));
        when(supplyCollectionService.refresh("announcement-100"))
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-supply", 2, 0, 1));

        mockMvc.perform(post("/api/admin/ingest/lh/announcements/details/announcement-100/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalApiCallCount").value(1));

        mockMvc.perform(post("/api/admin/ingest/lh/announcements/supplies/announcement-100/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalApiCallCount").value(1));
    }

    @Test
    void 확인한_공급_감소를_승인한_뒤_즉시_재조회한다() throws Exception {
        when(replacementService.approve(eq("announcement-100"), any(), eq("operator"))).thenReturn(1L);
        when(replacementService.finish(1L)).thenReturn(true);
        when(supplyCollectionService.refresh("announcement-100"))
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-supply", 1, 0, 1, 0, 0, 1));

        mockMvc.perform(post("/api/admin/ingest/lh/announcements/supplies/announcement-100/verified-replacement")
                        .principal(() -> "operator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestDescription":"PAN_ID=100&COLLECTION_VERSION=6",
                                 "proposedFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                 "evidenceUrl":"https://apply.lh.or.kr/notice","reason":"철회 공고 확인"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successfulRequestCount").value(1));
        verify(ownershipService, times(1)).acquire();
    }

    @Test
    void 감소하지_않은_새_응답도_정상_재조회_결과로_반환한다() throws Exception {
        when(replacementService.approve(eq("announcement-100"), any(), eq("operator"))).thenReturn(1L);
        when(replacementService.finish(1L)).thenReturn(false);
        when(supplyCollectionService.refresh("announcement-100"))
                .thenReturn(new ExternalDataCollectionReport("lh-announcement-supply", 2, 0, 1, 0, 0, 1));

        mockMvc.perform(post("/api/admin/ingest/lh/announcements/supplies/announcement-100/verified-replacement")
                        .principal(() -> "operator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestDescription":"PAN_ID=100&COLLECTION_VERSION=6",
                                 "proposedFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                 "evidenceUrl":"https://apply.lh.or.kr/notice","reason":"철회 공고 확인"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storedRowCount").value(2));
    }
}
