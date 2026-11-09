package com.toadzip.backend.ingest.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.enrichment.controller.LhAnnouncementEnrichmentController;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentService;
import com.toadzip.backend.ingest.mapping.controller.AnnouncementSupplyMatchingController;
import com.toadzip.backend.ingest.mapping.controller.LhSupplyLinkController;
import com.toadzip.backend.ingest.mapping.controller.MyHomeAnnouncementMappingController;
import com.toadzip.backend.ingest.mapping.controller.MyHomeComplexMappingController;
import com.toadzip.backend.ingest.mapping.service.AnnouncementSupplyCandidateService;
import com.toadzip.backend.ingest.mapping.service.AnnouncementSupplyRowQueryService;
import com.toadzip.backend.ingest.mapping.service.LhSupplyLinkService;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingService;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexMappingService;
import com.toadzip.backend.ingest.pipeline.controller.DataPipelineController;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionQueryService;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({MyHomeComplexMappingController.class, MyHomeAnnouncementMappingController.class,
        LhAnnouncementEnrichmentController.class, DataPipelineController.class,
        AnnouncementSupplyMatchingController.class, LhSupplyLinkController.class})
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

    @MockitoBean
    private AnnouncementSupplyRowQueryService rowQuery;

    @MockitoBean
    private AnnouncementSupplyCandidateService candidates;

    @MockitoBean
    private LhSupplyLinkService supplyLinks;

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/ingest/myhome/complex-mappings/failures",
            "/api/admin/ingest/myhome/announcement-mappings/failures",
            "/api/admin/ingest/lh/announcement-enrichments/failures"
    })
    void 삭제한_조회_API는_제공하지_않는다(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @CsvSource({
            "/api/admin/ingest/myhome/announcement-mappings, 404",
            "/api/admin/ingest/lh/announcement-enrichments, 404",
            "/api/admin/ingest/pipelines/announcement-registration/url, 404",
            "/api/admin/ingest/announcement-supply-matches/21026/refine, 404",
            "/api/admin/ingest/announcement-supply-matches/21026/rerun, 404",
            "/api/admin/ingest/announcement-supply-matches/21026/release, 404",
            "/api/admin/ingest/announcement-supply-matches/21026/exclusion, 404",
            "/api/admin/ingest/announcement-supply-matches/21026/split, 404",
            "/api/admin/ingest/announcement-supply-matches/21026/lh-links, 405",
            "/api/admin/ingest/announcement-supply-matches/21026/lh-links/release, 404"
    })
    void 지원을_종료한_마이홈_공고_정제와_그_보정_API는_제공하지_않는다(String path, int expected) throws Exception {
        mockMvc.perform(post(path)).andExpect(status().is(expected));

        verifyNoInteractions(announcementMappingService, enrichmentService, executionService, supplyLinks);
    }
}
