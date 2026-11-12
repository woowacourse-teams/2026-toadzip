package com.toadzip.backend.ingest.mapping.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationLocationRequest;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationResponse;
import com.toadzip.backend.ingest.mapping.service.MyHomeInitialRegistrationService;
import com.toadzip.backend.ingest.mapping.service.MyHomeInitialRegistrationSupplementService;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MyHomeInitialRegistrationController.class)
@AutoConfigureMockMvc(addFilters = false)
class MyHomeInitialRegistrationControllerTest {
    @MockitoBean private MyHomeInitialRegistrationSupplementService supplements;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MyHomeInitialRegistrationService service;

    @MockitoBean
    private DataPipelineExecutionService execution;

    @Test
    void 선택한_실패_보완_대상만_재처리하도록_접수한다() throws Exception {
        mvc.perform(post("/api/admin/ingest/myhome/initial-registration/retry")
                        .principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceComplexIdentifiers":["123:PERMANENT_RENTAL"]}
                                """))
                .andExpect(status().isAccepted());
        verify(execution).retryComplexRegistration(List.of("123:PERMANENT_RENTAL"), "admin");
    }

    @Test
    void 여러_페이지에서_선택한_이백곳_초과_대상을_접수한다() throws Exception {
        String identifiers = String.join(",", java.util.Collections.nCopies(201, "\"123:PERMANENT_RENTAL\""));
        mvc.perform(post("/api/admin/ingest/myhome/initial-registration/retry")
                        .principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceComplexIdentifiers\":[" + identifiers + "]}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void 재처리_대상이_만곳을_초과하면_거절한다() throws Exception {
        String identifiers = String.join(",", java.util.Collections.nCopies(10001, "\"123:PERMANENT_RENTAL\""));
        mvc.perform(post("/api/admin/ingest/myhome/initial-registration/retry")
                        .principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceComplexIdentifiers\":[" + identifiers + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 빈_재처리_대상은_거절한다() throws Exception {
        mvc.perform(post("/api/admin/ingest/myhome/initial-registration/retry")
                        .principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceComplexIdentifiers\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 준비된_신규_단지만_최초_등록할_수_있다() throws Exception {
        when(execution.start(DataPipelineType.COMPLEX_REGISTRATION, "admin"))
                .thenReturn(DataPipelineExecutionResponse.idle(DataPipelineType.COMPLEX_REGISTRATION));
        mvc.perform(post("/api/admin/ingest/myhome/initial-registration/register")
                        .principal(() -> "admin"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("COMPLEX_REGISTRATION"));
        verify(execution).start(DataPipelineType.COMPLEX_REGISTRATION, "admin");
    }

    @Test
    void 최초_등록_대상_진행_상태를_조회한다() throws Exception {
        when(service.find(null, 0, 50)).thenReturn(new InitialRegistrationResponse(
                Map.of(InitialRegistrationStatus.LOCATION_REQUIRED, 10L, InitialRegistrationStatus.COMPLETED, 90L),
                List.of(), 100, 0, 2));
        mvc.perform(get("/api/admin/ingest/myhome/initial-registration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.COMPLETED").value(90))
                .andExpect(jsonPath("$.counts.LOCATION_REQUIRED").value(10));
    }

    @Test
    void 상태와_페이지로_남은_대상을_조회한다() throws Exception {
        mvc.perform(get("/api/admin/ingest/myhome/initial-registration")
                        .param("status", "FAILED").param("page", "2").param("size", "20"))
                .andExpect(status().isOk());
        verify(service).find(InitialRegistrationStatus.FAILED, 2, 20);
    }

    @Test
    void 대상_확인_요청은_등록하지_않고_상태를_갱신한다() throws Exception {
        mvc.perform(post("/api/admin/ingest/myhome/initial-registration/refresh"))
                .andExpect(status().isNoContent());
        verify(service).refresh();
    }

    @Test
    void 주소와_지도용_좌표를_관리자_보완값으로_저장한다() throws Exception {
        mvc.perform(post("/api/admin/ingest/myhome/initial-registration/locations")
                        .principal(() -> "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceComplexIdentifier":"1:HAPPY_HOUSING", "address":"서울특별시 종로구 테스트로 1",
                                 "latitude":37.12345678, "longitude":127.12345678}
                                """))
                .andExpect(status().isNoContent());
        verify(service).supplementLocation(new InitialRegistrationLocationRequest(
                "1:HAPPY_HOUSING", "서울특별시 종로구 테스트로 1",
                new BigDecimal("37.12345678"), new BigDecimal("127.12345678")), "admin");
    }

    @Test
    void 좌표_누락과_범위_초과는_보완_요청에서_거절한다() throws Exception {
        mvc.perform(post("/api/admin/ingest/myhome/initial-registration/locations")
                        .principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceComplexIdentifier":"1:HAPPY_HOUSING", "address":"보완 주소",
                                 "latitude":91, "longitude":null}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 과도한_페이지_크기와_음수_페이지는_거절한다() throws Exception {
        mvc.perform(get("/api/admin/ingest/myhome/initial-registration").param("size", "201"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/ingest/myhome/initial-registration").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }
}
