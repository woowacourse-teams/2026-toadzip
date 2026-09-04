package com.toadzip.backend.ingest.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementMappingFailureResponse;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.service.MyHomeAnnouncementMappingService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MyHomeAnnouncementMappingController.class)
@AutoConfigureMockMvc(addFilters = false)
class MyHomeAnnouncementMappingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyHomeAnnouncementMappingService mappingService;

    @Test
    void 저장된_마이홈_원천의_공고와_공급행_매핑을_실행한다() throws Exception {
        when(mappingService.mapAll()).thenReturn(new MyHomeAnnouncementMappingReport(
                1, 0, 0, 2, 0, 0, 0, 1
        ));

        mockMvc.perform(post("/api/admin/ingest/myhome/announcement-mappings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAnnouncementCount").value(1))
                .andExpect(jsonPath("$.createdSupplyRowCount").value(2))
                .andExpect(jsonPath("$.deletedSupplyRowCount").value(0))
                .andExpect(jsonPath("$.failedSourceRowCount").value(1));

        verify(mappingService).mapAll();
    }

    @Test
    void 매핑에_실패한_원천과_사유를_조회한다() throws Exception {
        when(mappingService.findFailures()).thenReturn(List.of(new MyHomeAnnouncementMappingFailureResponse(
                "source-key",
                "21026",
                1,
                MyHomeAnnouncementMappingFailureReason.COMPLEX_NOT_FOUND,
                "PNU와 공급유형이 일치하는 단지가 없습니다.",
                Instant.parse("2026-08-28T00:00:00Z")
        )));

        mockMvc.perform(get("/api/admin/ingest/myhome/announcement-mappings/failures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceKey").value("source-key"))
                .andExpect(jsonPath("$[0].sourceAnnouncementIdentifier").value("21026"))
                .andExpect(jsonPath("$[0].sourceHouseSerialNumber").value(1))
                .andExpect(jsonPath("$[0].reason").value("COMPLEX_NOT_FOUND"));
    }
}
