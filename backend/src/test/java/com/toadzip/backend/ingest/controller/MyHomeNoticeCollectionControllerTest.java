package com.toadzip.backend.ingest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.toadzip.backend.ingest.dto.MyHomeNoticeCollectionRequest;
import com.toadzip.backend.ingest.service.MyHomeNoticeCollectionService;

@WebMvcTest(MyHomeNoticeCollectionController.class)
class MyHomeNoticeCollectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyHomeNoticeCollectionService collectionService;

    @Test
    void 기본_페이지_크기와_최대_페이지로_공고를_수집한다() throws Exception {
        mockMvc.perform(post("/api/admin/ingest/myhome/announcements"))
                .andExpect(status().isOk());

        ArgumentCaptor<MyHomeNoticeCollectionRequest> request = ArgumentCaptor.captor();
        verify(collectionService).collect(request.capture());
        assertThat(request.getValue().pageSize()).isEqualTo(10);
        assertThat(request.getValue().maxPages()).isEqualTo(1_000);
    }

    @Test
    void 기존_notice_수집_경로는_더_이상_노출하지_않는다() throws Exception {
        mockMvc.perform(post("/api/admin/ingest/myhome/notices"))
                .andExpect(status().isNotFound());
    }
}
