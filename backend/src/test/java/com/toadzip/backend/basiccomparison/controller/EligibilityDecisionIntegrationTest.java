package com.toadzip.backend.basiccomparison.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.basiccomparison.ComparisonFixtures;
import com.toadzip.backend.basiccomparison.EligibilityFixtures;
import com.toadzip.backend.basiccomparison.domain.ComparisonProfile;
import com.toadzip.backend.basiccomparison.domain.FamilyDetailsState;
import com.toadzip.backend.basiccomparison.domain.InputValue;
import com.toadzip.backend.basiccomparison.domain.ProfileField;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EligibilityDecisionIntegrationTest extends ComparisonIntegrationSupport {
    @Test
    void D05_수집_조건을_충족하면_지원_가능과_미수집_제한을_함께_반환한다() throws Exception {
        long announcement = announcement();
        publish(announcement, saveDraft(announcement, null, 0, EligibilityFixtures.d05()))
                .andExpect(status().isOk());
        jdbc.update("INSERT INTO basic_comparison_profiles (user_id, version, profile) VALUES (?, 1, ?::jsonb)",
                USER, json.writeValueAsString(ComparisonFixtures.b()));
        mvc.perform(get("/api/v2/announcements/" + announcement + "/eligibility")
                        .with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.result.status").value("ELIGIBLE"))
                .andExpect(jsonPath("$.data.result.options[0].pathLabel").value("일반입주자 신청자격"))
                .andExpect(jsonPath("$.data.result.options[0].exclusionGuidanceIds[0]").value("history"));
    }
    @Test
    void D11_공고의_정확한_법정자격과_넓은_탐색분류를_개인_입력과_구분한다() throws Exception {
        long announcement = announcement();
        publish(announcement, saveDraft(announcement, null, 0, EligibilityFixtures.d11()))
                .andExpect(status().isOk());
        profile(ComparisonFixtures.singleParent());
        var data = response(mvc.perform(get(path(announcement)).with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.status").value("UNDETERMINED")));
        assertThat(data.path("content").path("qualifications").toString())
                .contains("지원대상 한부모가족", "SINGLE_PARENT_FAMILY");
        assertThat(data.path("result").path("options").get(2).path("missingInputs").size()).isZero();
        assertThat(data.path("result").path("options").get(2).path("requiredQualificationIds").get(0).asString())
                .isEqualTo("single-parent");
    }

    @Test
    void D01_탐색지역의_가능한_선택지만_반환하고_공고전체_판정과_프로필은_유지한다() throws Exception {
        long announcement = announcement();
        publish(announcement, saveDraft(announcement, null, 0, EligibilityFixtures.d01()))
                .andExpect(status().isOk());
        profile(new ComparisonProfile(FamilyDetailsState.NOT_STARTED, Map.of(
                ProfileField.BIRTH_DATE, InputValue.provided("1997-01-10"),
                ProfileField.FAMILY_TYPE, InputValue.provided("UNMARRIED"))));
        mvc.perform(get(path(announcement)).param("region", "41465")
                        .with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.status").value("ELIGIBLE"))
                .andExpect(jsonPath("$.data.eligibleOptionIdsInRegion.length()").value(0));
        mvc.perform(get(path(announcement)).param("region", "41463")
                        .with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligibleOptionIdsInRegion[0]").value("giheung-youth"));
        assertThat(jdbc.queryForObject("SELECT version FROM basic_comparison_profiles WHERE user_id = ?",
                Long.class, USER)).isOne();
    }

    @Test
    void 판정조회에도_회원인증과_유효지역_검증을_적용한다() throws Exception {
        long announcement = announcement();
        mvc.perform(get(path(announcement))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(announcement)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get(path(announcement)).with(user("99999999").roles("USER")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path(announcement)).param("region", "unknown")
                        .with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isBadRequest());
    }

    private String path(long announcement) {
        return "/api/v2/announcements/" + announcement + "/eligibility";
    }

    private void profile(ComparisonProfile profile) {
        jdbc.update("INSERT INTO basic_comparison_profiles (user_id, version, profile) VALUES (?, 1, ?::jsonb)",
                USER, json.writeValueAsString(profile));
    }

}
