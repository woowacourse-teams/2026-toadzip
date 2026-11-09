package com.toadzip.backend.ingest.pipeline.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.exception.exception.DataPipelineExecutionNotFoundException;
import com.toadzip.backend.ingest.exception.exception.IngestServiceKeyMissingException;
import com.toadzip.backend.ingest.exception.exception.RetiredIngestOperationException;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineReadinessResponse;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionQueryService;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DataPipelineController.class)
@AutoConfigureMockMvc(addFilters = false)
class DataPipelineControllerTest {

    @Test
    void 없는_실행_ID의_상태_조회는_404를_반환한다() throws Exception {
        UUID executionId = UUID.randomUUID();
        when(executionQuery.find(executionId))
                .thenThrow(new DataPipelineExecutionNotFoundException("실행을 찾을 수 없습니다."));
        mockMvc.perform(get("/api/admin/ingest/pipelines/executions/{executionId}", executionId))
                .andExpect(status().isNotFound());
    }

    @Test
    void 실행_ID로_단건_상태를_조회한다() throws Exception {
        UUID executionId = UUID.randomUUID();
        mockMvc.perform(get("/api/admin/ingest/pipelines/executions/{executionId}", executionId))
                .andExpect(status().isOk());
        verify(executionQuery).find(executionId);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataPipelineExecutionService executionService;

    @MockitoBean
    private DataPipelineExecutionQueryService executionQuery;

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"serviceKey\":\"stale-client-test-key\"}", "{}", "{\"serviceKey\":null}"
    })
    void 수집_실행_본문은_키를_쓰거나_되돌리지_않고_거절한다(String body) throws Exception {
        var result = mockMvc.perform(post("/api/admin/ingest/pipelines/announcement-sync")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INGEST_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "수집 실행은 요청 본문을 받지 않습니다. 공공데이터포털 수집 키는 서버 설정으로만 사용합니다."))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .doesNotContain("stale-client-test-key");
        verifyNoInteractions(executionService);
    }

    @Test
    void 서버_수집_키가_없으면_503으로_키_설정을_안내한다() throws Exception {
        when(executionService.start(DataPipelineType.ANNOUNCEMENT_SYNC))
                .thenThrow(new IngestServiceKeyMissingException());

        mockMvc.perform(post("/api/admin/ingest/pipelines/announcement-sync"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INGEST_SERVICE_KEY_MISSING"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("DATA_GO_KR_SERVICE_KEY")));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void 준비_상태는_서버_수집_키_설정_여부만_돌려준다(boolean configured) throws Exception {
        when(executionService.readiness()).thenReturn(new DataPipelineReadinessResponse(configured));

        mockMvc.perform(get("/api/admin/ingest/pipelines/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceKeyConfigured").value(configured))
                .andExpect(jsonPath("$.serviceKey").doesNotExist());
    }

    @ParameterizedTest
    @CsvSource({
            "complex-collection, COMPLEX_COLLECTION",
            "announcement-collection, ANNOUNCEMENT_COLLECTION",
            "announcement-refinement, ANNOUNCEMENT_REFINEMENT",
            "announcement-sync, ANNOUNCEMENT_SYNC"
    })
    void 독립_및_통합_실행을_접수하고_진행_상태를_조회한다(
            String pathValue,
            DataPipelineType type
    ) throws Exception {
        DataPipelineExecutionResponse response = DataPipelineExecutionResponse.idle(type);
        when(executionService.start(type)).thenReturn(response);
        when(executionQuery.findLatest(type)).thenReturn(response);

        mockMvc.perform(post("/api/admin/ingest/pipelines/{type}", pathValue))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value(type.name()));

        mockMvc.perform(get("/api/admin/ingest/pipelines/{type}", pathValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"));
    }

    @ParameterizedTest
    @CsvSource({"complex-refinement, COMPLEX_REFINEMENT", "complex-sync, COMPLEX_SYNC"})
    void 지원을_종료한_단지_정제는_410을_반환하고_지난_상태는_조회한다(
            String pathValue,
            DataPipelineType type
    ) throws Exception {
        when(executionService.start(type)).thenThrow(RetiredIngestOperationException.complexRefinement());
        when(executionQuery.findLatest(type)).thenReturn(DataPipelineExecutionResponse.idle(type));

        mockMvc.perform(post("/api/admin/ingest/pipelines/{type}", pathValue))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INGEST_OPERATION_RETIRED"))
                .andExpect(jsonPath("$.message").value(
                        "단지 정제는 지원을 종료했습니다. 단지·주택형은 공고와 PDF 보정으로만 바뀝니다."));

        mockMvc.perform(get("/api/admin/ingest/pipelines/{type}", pathValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value(type.name()));
    }

    @Test
    void 지원을_종료한_단건_등록도_일반_실행_경로에서_410을_반환한다() throws Exception {
        DataPipelineType type = DataPipelineType.ANNOUNCEMENT_REGISTRATION;
        when(executionService.start(type)).thenThrow(RetiredIngestOperationException.myHomeAnnouncementRefinement());

        mockMvc.perform(post("/api/admin/ingest/pipelines/announcement-registration"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INGEST_OPERATION_RETIRED"));
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
        when(executionQuery.history(2, 20)).thenReturn(List.of(response));

        mockMvc.perform(get("/api/admin/ingest/pipelines/history")
                        .param("page", "2")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("ANNOUNCEMENT_COLLECTION"));

        verify(executionQuery).history(2, 20);
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
