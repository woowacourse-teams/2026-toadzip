package com.toadzip.backend.ingest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.service.LhLeaseCatalogCollectionService;

@WebMvcTest(LhLeaseCatalogCollectionController.class)
@AutoConfigureMockMvc(addFilters = false)
class LhLeaseCatalogCollectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LhLeaseCatalogCollectionService collectionService;

    @Test
    void LH_카탈로그_수집이_성공하면_200을_반환한다() throws Exception {
        when(collectionService.collect(any()))
                .thenReturn(new ExternalDataCollectionReport("lh-lease-catalog", 3, 0, 1));

        mockMvc.perform(post("/api/admin/ingest/lh/lease-catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storedRowCount").value(3))
                .andExpect(jsonPath("$.failedRequestCount").value(0));
    }

    @Test
    void LH_카탈로그_수집이_실패하면_502와_수집_결과를_반환한다() throws Exception {
        when(collectionService.collect(any()))
                .thenReturn(new ExternalDataCollectionReport("lh-lease-catalog", 0, 1, 3));

        mockMvc.perform(post("/api/admin/ingest/lh/lease-catalog"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.storedRowCount").value(0))
                .andExpect(jsonPath("$.failedRequestCount").value(1));
    }
}
