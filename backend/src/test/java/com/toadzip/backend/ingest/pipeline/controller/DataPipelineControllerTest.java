package com.toadzip.backend.ingest.pipeline.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.exception.exception.DataPipelineExecutionNotFoundException;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

@WebMvcTest(DataPipelineController.class)
@AutoConfigureMockMvc(addFilters = false)
class DataPipelineControllerTest {

    @Test
    void 실행_중지_요청을_접수한다() throws Exception {
        mockMvc.perform(post("/api/admin/ingest/pipelines/executions/{executionId}/stop", UUID.randomUUID()))
                .andExpect(status().isAccepted());
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataPipelineExecutionService executionService;

    @ParameterizedTest
    @CsvSource({
            "complex-collection, COMPLEX_COLLECTION", "announcement-collection, ANNOUNCEMENT_COLLECTION",
            "complex-sync, COMPLEX_SYNC", "announcement-sync, ANNOUNCEMENT_SYNC"
    })
    void 수집_실행은_입력키를_양끝_공백_제거해_전달하고_응답에_노출하지_않는다(
            String pathValue, DataPipelineType type
    ) throws Exception {
        mockMvc.perform(post("/api/admin/ingest/pipelines/{type}", pathValue)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"serviceKey\":\" transient-test-key \"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.serviceKey").doesNotExist());
        verify(executionService).start(type, "transient-test-key");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"{}", "{\"serviceKey\":null}",
            "{\"serviceKey\":\" \"}", "{\"serviceKey\":\"\"}"})
    void 입력_본문에_키가_없거나_비어_있으면_값을_노출하지_않고_거부한다(String body) throws Exception {
        mockMvc.perform(post("/api/admin/ingest/pipelines/complex-collection")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(executionService);
    }

    @Test
    void 최대_길이를_넘은_서비스키는_거부한다() throws Exception {
        String key = "x".repeat(4097);
        var result = mockMvc.perform(post("/api/admin/ingest/pipelines/complex-collection")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"serviceKey\":\"" + key + "\"}"))
                .andExpect(status().isBadRequest()).andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString()).doesNotContain(key);
        verifyNoInteractions(executionService);
    }

    @Test
    void 정제_실행은_서비스키_입력을_거부한다() throws Exception {
        when(executionService.start(DataPipelineType.COMPLEX_REFINEMENT, "test-key"))
                .thenThrow(new com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException(
                        "수집 작업에만 서비스키를 입력할 수 있습니다."));
        mockMvc.perform(post("/api/admin/ingest/pipelines/complex-refinement")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"serviceKey\":\"test-key\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INGEST_REQUEST"));
    }

    @ParameterizedTest
    @CsvSource({
            "complex-collection, COMPLEX_COLLECTION",
            "complex-refinement, COMPLEX_REFINEMENT",
            "announcement-collection, ANNOUNCEMENT_COLLECTION",
            "announcement-refinement, ANNOUNCEMENT_REFINEMENT",
            "complex-sync, COMPLEX_SYNC",
            "announcement-sync, ANNOUNCEMENT_SYNC"
    })
    void 독립_및_통합_실행을_접수하고_진행_상태를_조회한다(
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
    void 파이프라인_실행_이력을_페이지로_조회한다() throws Exception {
        DataPipelineExecutionResponse response = DataPipelineExecutionResponse.idle(
                DataPipelineType.ANNOUNCEMENT_COLLECTION
        );
        when(executionService.history(2, 20)).thenReturn(List.of(response));

        mockMvc.perform(get("/api/admin/ingest/pipelines/history")
                        .param("page", "2")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("ANNOUNCEMENT_COLLECTION"));

        verify(executionService).history(2, 20);
    }

    @Test
    void 존재하지_않는_실행_ID는_404를_반환한다() throws Exception {
        UUID executionId = UUID.randomUUID();
        when(executionService.requestStop(executionId)).thenThrow(
                new DataPipelineExecutionNotFoundException("실행 없음")
        );

        mockMvc.perform(post(
                        "/api/admin/ingest/pipelines/executions/{executionId}/stop",
                        executionId
                ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("DATA_PIPELINE_EXECUTION_NOT_FOUND"));
    }
}
