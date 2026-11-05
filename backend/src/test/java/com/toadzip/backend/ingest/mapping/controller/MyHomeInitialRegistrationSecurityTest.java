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
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import com.toadzip.backend.user.repository.UserRepository;
import com.toadzip.backend.user.service.SocialLoginFailureHandler;
import com.toadzip.backend.user.service.SocialLoginSuccessHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MyHomeInitialRegistrationController.class)
@Import({AdminSecurityConfiguration.class, SecurityConfiguration.class, ClockConfig.class,
        JsonAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class MyHomeInitialRegistrationSecurityTest {

    private static final String PATH = "/api/admin/ingest/myhome/initial-registration";
    @Autowired private MockMvc mvc;
    @MockitoBean private MyHomeInitialRegistrationService service;
    @MockitoBean private UserRepository users;
    @MockitoBean private SocialLoginSuccessHandler socialLoginSuccessHandler;
    @MockitoBean private SocialLoginFailureHandler socialLoginFailureHandler;
    @MockitoBean private PrivacyNoticeCatalog notices;

    @Test
    void 비로그인_사용자는_진행_상태를_읽을_수_없다() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void 일반_사용자는_상태를_갱신할_수_없다() throws Exception {
        when(users.existsById(1L)).thenReturn(true);
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
    void 관리자와_CSRF를_확인한_뒤_상태를_갱신한다() throws Exception {
        mvc.perform(post(PATH + "/refresh").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isNoContent());
        verify(service).refresh();
    }
}
