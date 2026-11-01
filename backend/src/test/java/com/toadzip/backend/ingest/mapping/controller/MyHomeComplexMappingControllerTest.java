package com.toadzip.backend.ingest.mapping.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingFailureResponse;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexMappingService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MyHomeComplexMappingController.class)
@AutoConfigureMockMvc(addFilters = false)
class MyHomeComplexMappingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyHomeComplexMappingService mappingService;

    @ParameterizedTest
    @ValueSource(strings = {"candidates", "batches"})
    void 단계별_정제_API는_제공하지_않는다(String path) throws Exception {
        mockMvc.perform(post("/api/admin/ingest/myhome/complex-mappings/{path}", path))
                .andExpect(status().isNotFound());
    }

    @Test
    void 단지_매핑_실행은_지원_종료로_410을_반환하고_매핑하지_않는다() throws Exception {
        mockMvc.perform(post("/api/admin/ingest/myhome/complex-mappings"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INGEST_OPERATION_RETIRED"))
                .andExpect(jsonPath("$.message").value(
                        "단지 정제는 지원을 종료했습니다. 단지·주택형은 공고와 PDF 보정으로만 바뀝니다."));

        verifyNoInteractions(mappingService);
    }

    @Test
    void 매핑에_실패한_원천과_사유를_조회한다() throws Exception {
        when(mappingService.findFailures(0, 100)).thenReturn(List.of(new MyHomeComplexMappingFailureResponse(
                "source-key",
                "123",
                MyHomeComplexMappingFailureReason.INVALID_VALUE,
                "준공일 형식이 올바르지 않습니다.",
                Instant.parse("2026-08-27T00:00:00Z")
        )));

        mockMvc.perform(get("/api/admin/ingest/myhome/complex-mappings/failures/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceKey").value("source-key"))
                .andExpect(jsonPath("$[0].sourceComplexIdentifier").value("123"))
                .andExpect(jsonPath("$[0].reason").value("INVALID_VALUE"))
                .andExpect(jsonPath("$[0].detail").value("준공일 형식이 올바르지 않습니다."));

        verify(mappingService).findFailures(0, 100);
    }

    @Test
    void 실패_이력을_요청한_페이지_범위로_조회한다() throws Exception {
        when(mappingService.findFailureHistory(2, 50)).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/ingest/myhome/complex-mappings/failures/history")
                        .param("page", "2")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(mappingService).findFailureHistory(2, 50);
    }

    @Test
    void 실패_조회_페이지_크기는_최대_200이다() throws Exception {
        mockMvc.perform(get("/api/admin/ingest/myhome/complex-mappings/failures/page")
                        .param("size", "201"))
                .andExpect(status().isBadRequest());

        verify(mappingService, never()).findFailures(0, 201);
    }
}
