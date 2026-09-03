package com.toadzip.backend.ingest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.dto.LocationSummaryImportReport;
import com.toadzip.backend.ingest.service.LocationSummaryImportService;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LocationSummaryImportController.class)
@AutoConfigureMockMvc(addFilters = false)
class LocationSummaryImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocationSummaryImportService importService;

    @Test
    void 월전체_ZIP에서_단지주소와_일치한_좌표만_선별_적재한다() throws Exception {
        when(importService.importMatches(eq("summary.zip"), any(InputStream.class)))
                .thenReturn(new LocationSummaryImportReport(
                        "summary.zip", 16, 6_422_078, 20_001, 19_900, 101,
                        21_000, 20_900, 0, List.of("11", "26", "50")
                ));
        MockMultipartFile file = new MockMultipartFile(
                "file", "summary.zip", "application/zip", new byte[]{1, 2, 3}
        );

        mockMvc.perform(multipart("/api/admin/ingest/juso/location-summaries").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scannedRowCount").value(6_422_078))
                .andExpect(jsonPath("$.targetRoadAddressCount").value(20_001))
                .andExpect(jsonPath("$.matchedRoadAddressCount").value(19_900))
                .andExpect(jsonPath("$.storedLocationCount").value(21_000));
    }

    @Test
    void 비어_있는_파일은_400으로_거절한다() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "summary.zip", "application/zip", new byte[0]
        );

        mockMvc.perform(multipart("/api/admin/ingest/juso/location-summaries").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INGEST_REQUEST"));
        verify(importService, never()).importMatches(any(), any());
    }
}
