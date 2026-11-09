package com.toadzip.backend.ingest.collection.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.collection.myhome.announcement.controller.MyHomeAnnouncementCollectionController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MyHomeAnnouncementCollectionController.class)
@AutoConfigureMockMvc(addFilters = false)
class MyHomeAnnouncementCollectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 마이홈_공고_수집은_지원_종료로_410을_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/ingest/myhome/announcements").param("pageSize", "10"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INGEST_OPERATION_RETIRED"))
                .andExpect(jsonPath("$.message").value(
                        "마이홈 공고 수집은 지원을 종료했습니다. 공고는 LH 공고목록 기준으로만 수집합니다."));
    }
}
