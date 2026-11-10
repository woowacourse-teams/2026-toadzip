package com.toadzip.backend.eligibility.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

class BasicComparisonCutoverIntegrationTest extends EligibilityIntegrationSupport {
    @Test
    void 구_입력_비교_요약_관리자_API는_더이상_실행하지_않는다() throws Exception {
        for (String path :
                List.of(
                        "/api/v1/me/eligibility-profile",
                        "/api/v1/eligibility/input-catalog",
                        "/api/v1/eligibility/summary",
                        "/api/v1/announcements/1/eligibility")) {
            mvc.perform(get(path).with(user(Long.toString(USER)).roles("USER")))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(
                        put("/api/v1/me/eligibility-profile")
                                .with(user(Long.toString(USER)).roles("USER"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":0,\"facts\":{}}"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/admin/announcements/1/eligibility-rule-set/draft")
                                .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/v2/eligibility/input-catalog")
                                .with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inputContractVersion").value(3));
    }
}
