package com.toadzip.backend.ingest.pipeline.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.dto.DataPipelineExecutionResponse;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataPipelineStartSecurityIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private com.toadzip.backend.user.repository.UserRepository privacyPermissionUsers;

    private String privacyPermissionMemberId() {
        var member = com.toadzip.backend.user.domain.User.create(
                "google:permission-" + java.util.UUID.randomUUID(), java.time.LocalDateTime.now());
        return privacyPermissionUsers.saveAndFlush(member).getId().toString();
    }


    private static final String ENDPOINT = "/api/admin/ingest/pipelines/complex-collection";
    private static final String BODY = "{\"serviceKey\":\"runtime-test-key\"}";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private DataPipelineExecutionService service;

    @ParameterizedTest
    @CsvSource({"anonymous,true,401", "USER,true,403", "ADMIN,false,403", "ADMIN,true,202"})
    void URL_등록도_관리자_인증과_CSRF를_요구한다(String role, boolean withCsrf, int expected) throws Exception {
        String url = "https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=21026";
        var request = post("/api/admin/ingest/pipelines/announcement-registration/url")
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + url + "\"}");
        if (!"anonymous".equals(role)) {
            String principalName = "account";
            if ("USER".equals(role)) {
                principalName = privacyPermissionMemberId();
            }
            request.with(user(principalName).roles(role));
        }
        if (withCsrf) {
            request.with(csrf());
        }
        mockMvc.perform(request).andExpect(status().is(expected));
        if (expected == 202) {
            verify(service).startAnnouncementRegistrationUrl(url);
            return;
        }
        verifyNoInteractions(service);
    }

    @Test
    void 비로그인_사용자는_단건_등록을_할_수_없다() throws Exception {
        mockMvc.perform(post("/api/admin/ingest/pipelines/announcement-registration").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pblancId\":\"21026\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void 일반_사용자는_단건_등록을_할_수_없다() throws Exception {
        mockMvc.perform(post("/api/admin/ingest/pipelines/announcement-registration")
                        .with(user(privacyPermissionMemberId()).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pblancId\":\"21026\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void 관리자_단건_등록도_CSRF가_필요하다() throws Exception {
        mockMvc.perform(post("/api/admin/ingest/pipelines/announcement-registration")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pblancId\":\"21026\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void 관리자는_서버_설정키로_단건_등록을_접수한다() throws Exception {
        mockMvc.perform(post("/api/admin/ingest/pipelines/announcement-registration")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pblancId\":\"21026\"}"))
                .andExpect(status().isAccepted());
        verify(service).startAnnouncementRegistration("21026");
    }

    @Test
    void 비로그인_사용자는_입력키로_수집을_시작할_수_없다() throws Exception {
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void 일반_사용자는_입력키로_수집을_시작할_수_없다() throws Exception {
        mockMvc.perform(post(ENDPOINT).with(user(privacyPermissionMemberId()).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void 관리자의_입력키_시작에도_CSRF_토큰이_필요하다() throws Exception {
        mockMvc.perform(post(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({
            "complex-collection, COMPLEX_COLLECTION", "announcement-collection, ANNOUNCEMENT_COLLECTION",
            "complex-sync, COMPLEX_SYNC", "announcement-sync, ANNOUNCEMENT_SYNC"
    })
    void 관리자는_CSRF와_입력키로_수집을_접수하며_키는_응답에_없다(
            String pathValue, DataPipelineType type
    ) throws Exception {
        when(service.start(type, "runtime-test-key"))
                .thenReturn(DataPipelineExecutionResponse.idle(type));
        var result = mockMvc.perform(post("/api/admin/ingest/pipelines/{type}", pathValue)
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.serviceKey").doesNotExist()).andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .doesNotContain("runtime-test-key");
        verify(service).start(type, "runtime-test-key");
    }

    @Test
    void 관리자의_기존_본문_없는_요청도_설정키_방식으로_접수한다() throws Exception {
        mockMvc.perform(post(ENDPOINT).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isAccepted());
        verify(service).start(DataPipelineType.COMPLEX_COLLECTION);
    }
}
