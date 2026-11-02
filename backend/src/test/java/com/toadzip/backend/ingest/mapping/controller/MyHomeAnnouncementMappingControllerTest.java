package com.toadzip.backend.ingest.mapping.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingFailureResponse;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingService;
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
    void 공고_매핑_실행은_지원_종료로_410을_반환하고_매핑하지_않는다() throws Exception {
        mockMvc.perform(post("/api/admin/ingest/myhome/announcement-mappings"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INGEST_OPERATION_RETIRED"))
                .andExpect(jsonPath("$.message").value(
                        "마이홈 공고 정제·LH 보강과 그 보정은 지원을 종료했습니다. 공고는 LH 공고 정제와 PDF 보정으로만 바뀝니다."));

        verifyNoInteractions(mappingService);
    }

    @Test
    void 매핑에_실패한_원천과_사유를_조회한다() throws Exception {
        when(mappingService.findFailures(2, 50)).thenReturn(List.of(new MyHomeAnnouncementMappingFailureResponse(
                "source-key",
                "21026",
                1,
                MyHomeAnnouncementMappingFailureReason.COMPLEX_NOT_FOUND,
                "PNU와 공급유형이 일치하는 단지가 없습니다.",
                Instant.parse("2026-08-28T00:00:00Z")
        )));

        mockMvc.perform(get("/api/admin/ingest/myhome/announcement-mappings/failures/page")
                        .param("page", "2")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceKey").value("source-key"))
                .andExpect(jsonPath("$[0].sourceAnnouncementIdentifier").value("21026"))
                .andExpect(jsonPath("$[0].sourceHouseSerialNumber").value(1))
                .andExpect(jsonPath("$[0].reason").value("COMPLEX_NOT_FOUND"));

        verify(mappingService).findFailures(2, 50);
    }
}
