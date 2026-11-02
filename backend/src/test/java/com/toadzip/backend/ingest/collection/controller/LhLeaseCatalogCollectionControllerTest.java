package com.toadzip.backend.ingest.collection.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.controller.LhLeaseCatalogCollectionController;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.service.LhLeaseCatalogCollectionService;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.service.LhLeaseCatalogCollector;
import com.toadzip.backend.ingest.collection.paging.service.PagedSourceCollector;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import java.time.Instant;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 원천 요청은 서비스가 만들므로 실제 서비스를 두고, 페이지 수집기는 요청만 만든 뒤 보고를 돌려준다.
@WebMvcTest(LhLeaseCatalogCollectionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(LhLeaseCatalogCollectionService.class)
@ExtendWith(IngestExecutionExtension.class)
class LhLeaseCatalogCollectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LhLeaseCatalogCollector collector;

    @MockitoBean
    private PagedSourceCollector pagedCollector;

    @Test
    void LH_카탈로그_수집이_성공하면_200을_반환한다() throws Exception {
        buildsRequestAndReports(new ExternalDataCollectionReport("lh-lease-catalog", 3, 0, 1));

        mockMvc.perform(post("/api/admin/ingest/lh/lease-catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storedRowCount").value(3))
                .andExpect(jsonPath("$.failedRequestCount").value(0));
    }

    @Test
    void LH_카탈로그_수집이_실패하면_502와_수집_결과를_반환한다() throws Exception {
        buildsRequestAndReports(new ExternalDataCollectionReport("lh-lease-catalog", 0, 1, 3));

        mockMvc.perform(post("/api/admin/ingest/lh/lease-catalog"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.storedRowCount").value(0))
                .andExpect(jsonPath("$.failedRequestCount").value(1));
    }

    @Test
    void 원천_요청이_허용하는_최대_페이지_범위는_수집한다() throws Exception {
        buildsRequestAndReports(new ExternalDataCollectionReport("lh-lease-catalog", 3, 0, 1));

        mockMvc.perform(post("/api/admin/ingest/lh/lease-catalog")
                        .param("pageSize", "9999")
                        .param("maxPages", "1000"))
                .andExpect(status().isOk());
    }

    @Test
    void 원천_요청의_페이지_범위를_넘으면_검증_오류로_반환한다() throws Exception {
        buildsRequestAndReports(new ExternalDataCollectionReport("lh-lease-catalog", 3, 0, 1));

        mockMvc.perform(post("/api/admin/ingest/lh/lease-catalog")
                        .param("pageSize", "10000")
                        .param("maxPages", "1001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("요청값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field == 'pageSize')].reason").value("9999 이하여야 합니다."))
                .andExpect(jsonPath("$.errors[?(@.field == 'maxPages')].reason").value("1000 이하여야 합니다."));
    }

    private void buildsRequestAndReports(ExternalDataCollectionReport report) {
        when(pagedCollector.collectAndReport(any(), any())).thenAnswer(invocation -> {
            invocation.<Function<Instant, ?>>getArgument(1).apply(Instant.now());
            return report;
        });
    }
}
