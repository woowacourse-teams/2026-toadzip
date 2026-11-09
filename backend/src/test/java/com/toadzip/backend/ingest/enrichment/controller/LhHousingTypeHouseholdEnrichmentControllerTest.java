package com.toadzip.backend.ingest.enrichment.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.enrichment.service.LhHouseholdComplexLinkService;
import com.toadzip.backend.ingest.enrichment.service.LhHouseholdTargetDecisionService;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdEnrichmentService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LhHousingTypeHouseholdEnrichmentController.class)
@AutoConfigureMockMvc(addFilters = false)
class LhHousingTypeHouseholdEnrichmentControllerTest {

    private static final String BASE_PATH = "/api/admin/ingest/lh/housing-type-households";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LhHousingTypeHouseholdEnrichmentService enrichmentService;

    @MockitoBean
    private LhHouseholdComplexLinkService linkService;

    @MockitoBean
    private LhHouseholdTargetDecisionService decisionService;

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "/failures/1/complex-link",
            "/failures/1/complex-link/release",
            "/failures/1/target-decision",
            "/failures/1/target-decision/release"
    })
    void 세대수_보강_실행과_연결_결정_저장은_지원_종료로_410을_반환한다(String path) throws Exception {
        mockMvc.perform(post(BASE_PATH + path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INGEST_OPERATION_RETIRED"))
                .andExpect(jsonPath("$.message").value(
                        "단지 정제는 지원을 종료했습니다. 단지·주택형은 공고와 PDF 보정으로만 바뀝니다."));

        verifyNoInteractions(enrichmentService, linkService, decisionService);
    }

    @Test
    void 세대수_보강_실패_목록은_계속_조회한다() throws Exception {
        when(enrichmentService.findFailures(0, 100)).thenReturn(List.of());

        mockMvc.perform(get(BASE_PATH + "/failures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(enrichmentService).findFailures(0, 100);
    }

    @Test
    void 단지_연결과_대상_결정은_계속_조회한다() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/failures/1/complex-link"))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE_PATH + "/failures/1/target-decision"))
                .andExpect(status().isOk());

        verify(linkService).view(1L);
        verify(decisionService).view(1L);
    }
}
