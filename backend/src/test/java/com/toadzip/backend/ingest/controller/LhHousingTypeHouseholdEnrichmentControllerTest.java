package com.toadzip.backend.ingest.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.dto.LhHousingTypeHouseholdEnrichmentReport;
import com.toadzip.backend.ingest.service.LhHousingTypeHouseholdEnrichmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LhHousingTypeHouseholdEnrichmentController.class)
@AutoConfigureMockMvc(addFilters = false)
class LhHousingTypeHouseholdEnrichmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LhHousingTypeHouseholdEnrichmentService enrichmentService;

    @Test
    void LH_카탈로그로_마이홈_주택형별_세대수_보강을_실행한다() throws Exception {
        when(enrichmentService.enrichAll()).thenReturn(
                new LhHousingTypeHouseholdEnrichmentReport(1, 1, 2, 0, 0, 0)
        );

        mockMvc.perform(post("/api/admin/ingest/lh/housing-type-households"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceComplexCount").value(1))
                .andExpect(jsonPath("$.matchedComplexCount").value(1))
                .andExpect(jsonPath("$.updatedHousingTypeCount").value(2))
                .andExpect(jsonPath("$.failedSourceComplexCount").value(0));
    }
}
