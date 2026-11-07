package com.toadzip.backend.eligibility.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.eligibility.BasicComparisonFixtures;

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

    @Test
    void 구_공개본은_남아있어도_새_비교에_사용하거나_공개하면서_삭제하지_않는다() throws Exception {
        long announcement = announcement();
        jdbc.update(
                """
                INSERT INTO announcement_rule_sets (announcement_id, status, recruitment_kind, applicant_kind,
                    has_eligibility_relaxation, collected_at, eligibility_reference_date, structuring_status,
                    version, evaluation_snapshot, published_at)
                VALUES (?, 'PUBLISHED', 'INITIAL', 'INDIVIDUAL', false, now(), '2026-10-16', 'COMPLETE', 1, '{}', now())
                """,
                announcement);
        String path = "/api/v2/announcements/" + announcement + "/basic-comparison";
        mvc.perform(get(path).with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availability").value("NOT_STRUCTURED"))
                .andExpect(jsonPath("$.data.result").isEmpty());
        mvc.perform(
                        get("/api/v2/eligibility/summary")
                                .with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notStructuredCount").value(1))
                .andExpect(jsonPath("$.data.comparedAnnouncementCount").value(0));
        publish(announcement, saveDraft(announcement, null, 0, BasicComparisonFixtures.sacheon()))
                .andExpect(status().isOk());
        mvc.perform(get(path).with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availability").value("AVAILABLE"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM announcement_rule_sets WHERE announcement_id"
                                    + " = ?",
                                Long.class,
                                announcement))
                .isOne();
    }
}
