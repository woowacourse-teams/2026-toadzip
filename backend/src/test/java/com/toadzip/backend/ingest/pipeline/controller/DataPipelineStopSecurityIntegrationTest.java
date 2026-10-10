package com.toadzip.backend.ingest.pipeline.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.exception.exception.DataPipelineExecutionNotFoundException;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataPipelineStopSecurityIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private com.toadzip.backend.user.repository.UserRepository privacyPermissionUsers;

    private String privacyPermissionMemberId() {
        var member = com.toadzip.backend.user.domain.User.create(
                "google:permission-" + java.util.UUID.randomUUID(), java.time.LocalDateTime.now());
        return privacyPermissionUsers.saveAndFlush(member).getId().toString();
    }


    private static final String ENDPOINT = "/api/admin/ingest/pipelines/executions/{id}/stop";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private DataPipelineExecutionService service;

    @Test
    void 인증되지_않은_사용자는_중지할_수_없다() throws Exception {
        mockMvc.perform(post(ENDPOINT, UUID.randomUUID()).with(csrf()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void 일반_사용자는_중지할_수_없다() throws Exception {
        mockMvc.perform(post(ENDPOINT, UUID.randomUUID()).with(user(privacyPermissionMemberId()).roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void 관리자도_CSRF_토큰_없이_중지할_수_없다() throws Exception {
        mockMvc.perform(post(ENDPOINT, UUID.randomUUID()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void 관리자의_올바른_요청은_실행_ID로_중지를_접수한다() throws Exception {
        mockMvc.perform(post(ENDPOINT, UUID.randomUUID()).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isAccepted());
    }

    @Test
    void 없는_실행을_중지하면_404를_반환한다() throws Exception {
        when(service.requestStop(any())).thenThrow(new DataPipelineExecutionNotFoundException("실행 없음"));
        mockMvc.perform(post(ENDPOINT, UUID.randomUUID()).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isNotFound());
    }
}
