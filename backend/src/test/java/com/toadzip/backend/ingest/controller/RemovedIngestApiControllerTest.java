package com.toadzip.backend.ingest.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.enrichment.controller.LhAnnouncementEnrichmentController;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentService;
import com.toadzip.backend.ingest.mapping.controller.MyHomeAnnouncementMappingController;
import com.toadzip.backend.ingest.mapping.controller.MyHomeComplexMappingController;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingService;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexMappingService;
import com.toadzip.backend.ingest.pipeline.controller.DataPipelineController;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
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

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/ingest/myhome/complex-mappings/failures",
            "/api/admin/ingest/myhome/announcement-mappings/failures",
            "/api/admin/ingest/lh/announcement-enrichments/failures"
    })
    void 삭제한_조회_API는_제공하지_않는다(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isNotFound());
    }
}
