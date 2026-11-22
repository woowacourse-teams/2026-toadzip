package com.toadzip.backend.announcement.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.admin.configuration.AdminSecurityConfiguration;
import com.toadzip.backend.announcement.domain.PdfExtractionInput.PublicationMode;
import com.toadzip.backend.announcement.dto.response.PdfAutoExtractionStatus;
import com.toadzip.backend.announcement.service.PdfAutoExtractionSettingService;
import com.toadzip.backend.global.config.ClockConfig;
import com.toadzip.backend.global.security.JsonAccessDeniedHandler;
import com.toadzip.backend.global.security.JsonAuthenticationEntryPoint;
import com.toadzip.backend.global.security.SecurityConfiguration;
import com.toadzip.backend.global.security.SecurityErrorResponseWriter;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import com.toadzip.backend.user.repository.UserRepository;
import com.toadzip.backend.user.service.SocialLoginFailureHandler;
import com.toadzip.backend.user.service.SocialLoginSuccessHandler;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PdfAutoExtractionSettingController.class)
@Import({AdminSecurityConfiguration.class, SecurityConfiguration.class, ClockConfig.class,
        JsonAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class PdfAutoExtractionSettingSecurityTest {
    private static final String ENDPOINT = "/api/admin/operations/pdf-auto-extraction";
    private static final String ON = "{\"enabled\":true}";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PdfAutoExtractionSettingService service;
    @MockitoBean private UserRepository users;
    @MockitoBean private SocialLoginSuccessHandler socialLoginSuccessHandler;
    @MockitoBean private SocialLoginFailureHandler socialLoginFailureHandler;
    @MockitoBean private PrivacyNoticeCatalog notices;

    @Test
    void 비로그인_사용자는_상태를_보거나_바꿀_수_없다() throws Exception {
        mockMvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(ON))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void 일반_사용자는_상태를_보거나_바꿀_수_없다() throws Exception {
        when(users.findAdminById(1L)).thenReturn(Optional.of(false));
        mockMvc.perform(get(ENDPOINT).with(user("1").roles("USER"))).andExpect(status().isForbidden());
        mockMvc.perform(put(ENDPOINT).with(user("1").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(ON))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void 관리자도_CSRF_토큰_없이_바꿀_수_없다() throws Exception {
        mockMvc.perform(put(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(ON))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void 켜짐_여부가_없는_요청은_바꾸지_않고_거절한다() throws Exception {
        mockMvc.perform(put(ENDPOINT).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void 관리자가_CSRF와_함께_바꾸면_로그인한_관리자를_바꾼_사람으로_넘긴다() throws Exception {
        Instant now = Instant.parse("2026-11-22T01:00:00Z");
        when(service.change(true, "admin")).thenReturn(
                new PdfAutoExtractionStatus(true, "admin", now, PublicationMode.APPLY, 85, 0, 2, now));

        mockMvc.perform(put(ENDPOINT).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(ON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.updatedBy").value("admin"))
                .andExpect(jsonPath("$.data.publicationMode").value("APPLY"))
                .andExpect(jsonPath("$.data.notExtracted").value(85));
        verify(service).change(true, "admin");
    }
}
