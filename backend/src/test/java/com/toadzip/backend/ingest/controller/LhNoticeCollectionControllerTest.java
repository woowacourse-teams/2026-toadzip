package com.toadzip.backend.ingest.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.service.LhNoticeDetailCollectionService;
import com.toadzip.backend.ingest.service.LhNoticeSupplyCollectionService;

@WebMvcTest(LhNoticeCollectionController.class)
class LhNoticeCollectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LhNoticeDetailCollectionService detailCollectionService;

    @MockitoBean
    private LhNoticeSupplyCollectionService supplyCollectionService;

    @Test
    void LH_상세와_공급_원본을_서로_다른_경로에서_수집한다() throws Exception {
        when(detailCollectionService.collect())
                .thenReturn(new ExternalDataCollectionReport("lh-notice-detail", 1, 0, 1));
        when(supplyCollectionService.collect())
                .thenReturn(new ExternalDataCollectionReport("lh-notice-supply", 2, 0, 1));

        mockMvc.perform(post("/api/admin/ingest/lh/notices/details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("lh-notice-detail"))
                .andExpect(jsonPath("$.storedRowCount").value(1));

        mockMvc.perform(post("/api/admin/ingest/lh/notices/supplies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("lh-notice-supply"))
                .andExpect(jsonPath("$.storedRowCount").value(2));
    }

    @Test
    void LH_상세_수집의_중복_실행은_409를_반환한다() throws Exception {
        when(detailCollectionService.collect())
                .thenThrow(new IngestAlreadyRunningException(
                        "lh-notice-detail 수집이 이미 실행 중입니다."
                ));

        mockMvc.perform(post("/api/admin/ingest/lh/notices/details"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INGEST_ALREADY_RUNNING"))
                .andExpect(jsonPath("$.message").value("lh-notice-detail 수집이 이미 실행 중입니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
