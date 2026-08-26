package com.toadzip.backend.ingest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.service.MyHomeAnnouncementCollectionService;

@WebMvcTest(MyHomeAnnouncementCollectionController.class)
class MyHomeAnnouncementCollectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyHomeAnnouncementCollectionService collectionService;

    @Test
    void 기본_페이지_크기와_최대_페이지로_공고를_수집한다() throws Exception {
        when(collectionService.collect(any()))
                .thenReturn(new ExternalDataCollectionReport("myhome-announcement", 3, 0, 2));

        mockMvc.perform(post("/api/admin/ingest/myhome/announcements"))
                .andExpect(status().isOk());

        ArgumentCaptor<MyHomeAnnouncementCollectionRequest> request = ArgumentCaptor.captor();
        verify(collectionService).collect(request.capture());
        assertThat(request.getValue().pageSize()).isEqualTo(10);
        assertThat(request.getValue().maxPages()).isEqualTo(1_000);
    }

    @Test
    void 공급_유형_수집이_실패하면_502와_수집_결과를_반환한다() throws Exception {
        when(collectionService.collect(any()))
                .thenReturn(new ExternalDataCollectionReport("myhome-announcement", 3, 1, 5));

        mockMvc.perform(post("/api/admin/ingest/myhome/announcements"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.storedRowCount").value(3))
                .andExpect(jsonPath("$.failedRequestCount").value(1));
    }
}
