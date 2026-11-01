package com.toadzip.backend.ingest.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.enrichment.controller.LhAnnouncementEnrichmentController;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentService;
import com.toadzip.backend.ingest.mapping.controller.MyHomeAnnouncementMappingController;
import com.toadzip.backend.ingest.mapping.controller.MyHomeComplexMappingController;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingService;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexMappingService;
import com.toadzip.backend.ingest.pipeline.controller.DataPipelineController;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionQueryService;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({MyHomeComplexMappingController.class, MyHomeAnnouncementMappingController.class,
        LhAnnouncementEnrichmentController.class, DataPipelineController.class})
@AutoConfigureMockMvc(addFilters = false)
class RemovedIngestApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyHomeComplexMappingService complexMappingService;

    @MockitoBean
    private MyHomeAnnouncementMappingService announcementMappingService;

    @MockitoBean
    private LhAnnouncementEnrichmentService enrichmentService;

    @MockitoBean
    private DataPipelineExecutionService executionService;

    @MockitoBean
    private DataPipelineExecutionQueryService executionQuery;

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/ingest/myhome/complex-mappings/failures",
            "/api/admin/ingest/myhome/announcement-mappings/failures",
            "/api/admin/ingest/lh/announcement-enrichments/failures"
    })
    void 삭제한_조회_API는_제공하지_않는다(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isNotFound());
    }

    @Test
    void LH_공고_보강_실행은_지원_종료로_410을_반환하고_보강하지_않는다() throws Exception {
        mockMvc.perform(post("/api/admin/ingest/lh/announcement-enrichments"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INGEST_OPERATION_RETIRED"))
                .andExpect(jsonPath("$.message").value(
                        "마이홈 공고 정제·LH 보강과 그 보정은 지원을 종료했습니다. 공고는 LH 공고 정제와 PDF 보정으로만 바뀝니다."));

        verifyNoInteractions(enrichmentService);
    }
}
