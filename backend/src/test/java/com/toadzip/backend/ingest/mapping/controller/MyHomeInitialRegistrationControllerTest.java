package com.toadzip.backend.ingest.mapping.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationResponse;
import com.toadzip.backend.ingest.mapping.service.MyHomeInitialRegistrationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MyHomeInitialRegistrationController.class)
@AutoConfigureMockMvc(addFilters = false)
class MyHomeInitialRegistrationControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MyHomeInitialRegistrationService service;

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
    void 과도한_페이지_크기와_음수_페이지는_거절한다() throws Exception {
        mvc.perform(get("/api/admin/ingest/myhome/initial-registration").param("size", "201"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/ingest/myhome/initial-registration").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }
}
