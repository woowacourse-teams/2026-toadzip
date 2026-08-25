package com.toadzip.backend.ingest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.ArgumentCaptor;

import com.toadzip.backend.ingest.dto.InvalidIngestRequestException;
import com.toadzip.backend.ingest.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.service.MyHomeComplexCollectionService;

@WebMvcTest(MyHomeComplexCollectionController.class)
class MyHomeComplexCollectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyHomeComplexCollectionService collectionService;

    @Test
    void 기본_페이지_크기와_최대_페이지로_전체_지역을_수집한다() throws Exception {
        when(collectionService.collect(any()))
                .thenReturn(new MyHomeComplexCollectionReport("myhome-complex", 3, 1, 7));

        mockMvc.perform(post("/api/admin/ingest/myhome/complexes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storedApiDataCount").value(3))
                .andExpect(jsonPath("$.failedRequestCount").value(1))
                .andExpect(jsonPath("$.externalApiCallCount").value(7));

        ArgumentCaptor<MyHomeComplexCollectionRequest> request = ArgumentCaptor.captor();
        verify(collectionService).collect(request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getValue().pageSize()).isEqualTo(10);
        org.assertj.core.api.Assertions.assertThat(request.getValue().maxPages()).isEqualTo(1_000);
    }

    @Test
    void 시도_코드만_전달하면_공통_오류_응답과_함께_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/ingest/myhome/complexes")
                        .param("provinceCode", "11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INGEST_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("시·도 코드와 시·군·구 코드는 함께 입력해야 합니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        verify(collectionService, never()).collect(any());
    }

    @Test
    void 존재하지_않는_지역_코드는_공통_오류_응답과_함께_400을_반환한다() throws Exception {
        when(collectionService.collect(any()))
                .thenThrow(new InvalidIngestRequestException("마이홈 지역 코드가 존재하지 않습니다."));

        mockMvc.perform(post("/api/admin/ingest/myhome/complexes")
                        .param("provinceCode", "99")
                        .param("districtCode", "999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INGEST_REQUEST"))
                .andExpect(jsonPath("$.message").value("마이홈 지역 코드가 존재하지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 허용_범위를_벗어난_숫자_파라미터는_공통_오류_응답과_함께_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/ingest/myhome/complexes")
                        .param("pageSize", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INGEST_REQUEST"))
                .andExpect(jsonPath("$.message").value("요청 파라미터가 허용 범위를 벗어났습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        verify(collectionService, never()).collect(any());
    }

    @Test
    void 숫자_파라미터에_문자열을_전달하면_공통_오류_응답과_함께_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/ingest/myhome/complexes")
                        .param("pageSize", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INGEST_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("요청 파라미터 형식이 올바르지 않습니다: pageSize"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        verify(collectionService, never()).collect(any());
    }
}
