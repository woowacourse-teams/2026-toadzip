package com.toadzip.backend.announcement.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.service.AdminAnnouncementImportRegistrationService;
import com.toadzip.backend.announcement.service.AdminAnnouncementImportValidationService;
import com.toadzip.backend.global.exception.GlobalExceptionAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminAnnouncementImportController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({AnnouncementExceptionAdvice.class, GlobalExceptionAdvice.class})
class AdminAnnouncementImportControllerTest {

    private static final String VALIDATE_ENDPOINT = "/api/admin/announcement-imports/validate";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminAnnouncementImportValidationService validationService;

    @MockitoBean
    private AdminAnnouncementImportRegistrationService registrationService;

    @Test
    void 정의되지_않은_추가_필드는_거부한다() throws Exception {
        mockMvc.perform(post(VALIDATE_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "schemaVersion": "admin-announcement-import/v1",
                                  "unexpected": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
