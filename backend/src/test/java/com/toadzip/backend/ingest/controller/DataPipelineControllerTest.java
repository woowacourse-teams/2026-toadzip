package com.toadzip.backend.ingest.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.domain.DataPipelineType;
import com.toadzip.backend.ingest.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.service.DataPipelineExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DataPipelineController.class)
@AutoConfigureMockMvc(addFilters = false)
class DataPipelineControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataPipelineExecutionService executionService;

    @Test
    void 수집_실행을_접수하고_진행_상태를_조회한다() throws Exception {
        DataPipelineExecutionResponse response = DataPipelineExecutionResponse.idle(
                DataPipelineType.COLLECTION
        );
        when(executionService.start(DataPipelineType.COLLECTION)).thenReturn(response);
        when(executionService.findLatest(DataPipelineType.COLLECTION)).thenReturn(response);

        mockMvc.perform(post("/api/admin/ingest/pipelines/collection"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("COLLECTION"));

        mockMvc.perform(get("/api/admin/ingest/pipelines/collection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"));
    }

    @Test
    void 알_수_없는_파이프라인은_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/ingest/pipelines/unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INGEST_REQUEST"));
    }
}
