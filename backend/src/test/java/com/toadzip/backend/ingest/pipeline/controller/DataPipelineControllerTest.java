package com.toadzip.backend.ingest.pipeline.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import com.toadzip.backend.ingest.exception.exception.DataPipelineExecutionNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    @ParameterizedTest
    @CsvSource({
            "complex-collection, COMPLEX_COLLECTION",
            "complex-refinement, COMPLEX_REFINEMENT",
            "announcement-collection, ANNOUNCEMENT_COLLECTION",
            "announcement-refinement, ANNOUNCEMENT_REFINEMENT"
    })
    void 분리된_실행을_접수하고_진행_상태를_조회한다(
            String pathValue,
            DataPipelineType type
    ) throws Exception {
        DataPipelineExecutionResponse response = DataPipelineExecutionResponse.idle(type);
        when(executionService.start(type)).thenReturn(response);
        when(executionService.findLatest(type)).thenReturn(response);

        mockMvc.perform(post("/api/admin/ingest/pipelines/{type}", pathValue))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value(type.name()));

        mockMvc.perform(get("/api/admin/ingest/pipelines/{type}", pathValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"));
    }

    @Test
    void 알_수_없는_파이프라인은_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/ingest/pipelines/unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INGEST_REQUEST"));
    }

    @Test
    void 실행_ID로_파이프라인_결과를_조회한다() throws Exception {
        UUID executionId = UUID.randomUUID();
        DataPipelineExecutionResponse response = DataPipelineExecutionResponse.idle(
                DataPipelineType.ANNOUNCEMENT_COLLECTION
        );
        when(executionService.find(executionId)).thenReturn(response);

        mockMvc.perform(get(
                        "/api/admin/ingest/pipelines/executions/{executionId}",
                        executionId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ANNOUNCEMENT_COLLECTION"));
    }

    @Test
    void 존재하지_않는_실행_ID는_404를_반환한다() throws Exception {
        UUID executionId = UUID.randomUUID();
        when(executionService.find(executionId)).thenThrow(
                new DataPipelineExecutionNotFoundException("실행 없음")
        );

        mockMvc.perform(get(
                        "/api/admin/ingest/pipelines/executions/{executionId}",
                        executionId
                ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("DATA_PIPELINE_EXECUTION_NOT_FOUND"));
    }
}
