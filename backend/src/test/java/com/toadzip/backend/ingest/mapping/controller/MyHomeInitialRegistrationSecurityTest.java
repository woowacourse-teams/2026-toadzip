package com.toadzip.backend.ingest.mapping.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.admin.configuration.AdminSecurityConfiguration;
import com.toadzip.backend.global.config.ClockConfig;
import com.toadzip.backend.global.security.JsonAccessDeniedHandler;
import com.toadzip.backend.global.security.JsonAuthenticationEntryPoint;
import com.toadzip.backend.global.security.SecurityConfiguration;
import com.toadzip.backend.global.security.SecurityErrorResponseWriter;
import com.toadzip.backend.ingest.mapping.service.MyHomeInitialRegistrationService;
import com.toadzip.backend.ingest.mapping.service.MyHomeInitialRegistrationSupplementService;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionService;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import com.toadzip.backend.user.repository.UserRepository;
import com.toadzip.backend.user.service.SocialLoginFailureHandler;
import com.toadzip.backend.user.service.SocialLoginSuccessHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MyHomeInitialRegistrationController.class)
@Import({AdminSecurityConfiguration.class, SecurityConfiguration.class, ClockConfig.class,
        JsonAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class MyHomeInitialRegistrationSecurityTest {
    @MockitoBean private MyHomeInitialRegistrationSupplementService supplements;

    private static final String PATH = "/api/admin/ingest/myhome/initial-registration";

    @Test
    void 재처리와_정보_보완은_관리자와_CSRF가_필요하다() throws Exception {
        for (String operation : java.util.List.of("retry", "information")) {
            mvc.perform(post(PATH + "/" + operation).with(user("admin").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            when(users.findAdminById(1L)).thenReturn(java.util.Optional.of(false));
            mvc.perform(post(PATH + "/" + operation).with(user("1").roles("USER")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(execution, supplements);
        mvc.perform(post(PATH + "/retry").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceComplexIdentifiers\":[\"123:PERMANENT_RENTAL\"]}"))
                .andExpect(status().isAccepted());
        verify(execution).retryComplexRegistration(java.util.List.of("123:PERMANENT_RENTAL"), "admin");
    }
    @Autowired private MockMvc mvc;
    @MockitoBean private MyHomeInitialRegistrationService service;
    @MockitoBean private DataPipelineExecutionService execution;
    @MockitoBean private UserRepository users;
    @MockitoBean private SocialLoginSuccessHandler socialLoginSuccessHandler;
    @MockitoBean private SocialLoginFailureHandler socialLoginFailureHandler;
    @MockitoBean private PrivacyNoticeCatalog notices;

    @Test
    void 등록은_관리자와_CSRF가_필요하다() throws Exception {
        mvc.perform(post(PATH + "/register").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        when(users.findAdminById(1L)).thenReturn(java.util.Optional.of(false));
        mvc.perform(post(PATH + "/register").with(user("1").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(execution);
        mvc.perform(post(PATH + "/register").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isAccepted());
        verify(execution).start(DataPipelineType.COMPLEX_REGISTRATION, "admin");
    }

    @Test
    void 비로그인_사용자는_진행_상태를_읽을_수_없다() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void 일반_사용자는_상태를_갱신할_수_없다() throws Exception {
        when(users.findAdminById(1L)).thenReturn(java.util.Optional.of(false));
        mvc.perform(post(PATH + "/refresh").with(user("1").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void 관리자도_CSRF_없이_상태를_갱신할_수_없다() throws Exception {
        mvc.perform(post(PATH + "/refresh").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void 관리자도_CSRF_없이는_주소_좌표를_보완할_수_없다() throws Exception {
        mvc.perform(post(PATH + "/locations").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void 일반_사용자는_주소_좌표를_보완할_수_없다() throws Exception {
        when(users.findAdminById(1L)).thenReturn(java.util.Optional.of(false));
        mvc.perform(post(PATH + "/locations").with(user("1").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void 관리자와_CSRF를_확인한_뒤_상태를_갱신한다() throws Exception {
        mvc.perform(post(PATH + "/refresh").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isNoContent());
        verify(service).refresh();
    }
}
