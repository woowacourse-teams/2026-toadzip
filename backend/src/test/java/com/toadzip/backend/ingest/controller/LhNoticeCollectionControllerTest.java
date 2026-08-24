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

import com.toadzip.backend.ingest.service.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.service.LhNoticeCollectionService;

@WebMvcTest(LhNoticeCollectionController.class)
class LhNoticeCollectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LhNoticeCollectionService collectionService;

    @Test
    void LH_공고_수집이_이미_실행_중이면_공통_오류_응답과_함께_409를_반환한다() throws Exception {
        when(collectionService.collect())
                .thenThrow(new IngestAlreadyRunningException("LH 공고 수집이 이미 실행 중입니다."));

        mockMvc.perform(post("/api/admin/ingest/lh/notices"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INGEST_ALREADY_RUNNING"))
                .andExpect(jsonPath("$.message").value("LH 공고 수집이 이미 실행 중입니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
