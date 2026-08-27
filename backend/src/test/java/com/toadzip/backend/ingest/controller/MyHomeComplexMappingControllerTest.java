package com.toadzip.backend.ingest.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.domain.MyHomeComplexMappingFailureReason;
import com.toadzip.backend.ingest.dto.MyHomeComplexMappingFailureResponse;
import com.toadzip.backend.ingest.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.service.MyHomeComplexMappingService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MyHomeComplexMappingController.class)
class MyHomeComplexMappingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyHomeComplexMappingService mappingService;

    @Test
    void 저장된_마이홈_원천의_단지와_주택형_매핑을_실행한다() throws Exception {
        when(mappingService.mapAll()).thenReturn(new MyHomeComplexMappingReport(
                1, 0, 0, 2, 0, 0, 0, 0
        ));

        mockMvc.perform(post("/api/admin/ingest/myhome/complex-mappings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdComplexCount").value(1))
                .andExpect(jsonPath("$.createdHousingTypeCount").value(2))
                .andExpect(jsonPath("$.failedSourceRowCount").value(0));

        verify(mappingService).mapAll();
    }

    @Test
    void 매핑에_실패한_원천과_사유를_조회한다() throws Exception {
        when(mappingService.findFailures()).thenReturn(List.of(new MyHomeComplexMappingFailureResponse(
                "source-key",
                "123",
                MyHomeComplexMappingFailureReason.INVALID_VALUE,
                "준공일 형식이 올바르지 않습니다.",
                Instant.parse("2026-08-27T00:00:00Z")
        )));

        mockMvc.perform(get("/api/admin/ingest/myhome/complex-mappings/failures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceKey").value("source-key"))
                .andExpect(jsonPath("$[0].sourceComplexIdentifier").value("123"))
                .andExpect(jsonPath("$[0].reason").value("INVALID_VALUE"))
                .andExpect(jsonPath("$[0].detail").value("준공일 형식이 올바르지 않습니다."));
    }
}
