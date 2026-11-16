package com.toadzip.backend.eligibility.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.eligibility.BasicComparisonFixtures;
import com.toadzip.backend.eligibility.EligibilityFixtures;
import com.toadzip.backend.eligibility.domain.EligibilityProfile;
import com.toadzip.backend.eligibility.domain.FamilyDetailsState;
import com.toadzip.backend.eligibility.domain.InputValue;
import com.toadzip.backend.eligibility.domain.ProfileField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EligibilityDecisionIntegrationTest extends EligibilityIntegrationSupport {
    @org.springframework.beans.factory.annotation.Autowired
    com.toadzip.backend.eligibility.service.EligibilityRecheckWorker worker;

    @Test
    void D05_수집_조건을_충족하면_지원_가능과_미수집_제한을_함께_반환한다() throws Exception {
        long announcement = announcement();
        publish(announcement, saveDraft(announcement, null, 0, EligibilityFixtures.d05()))
                .andExpect(status().isOk());
        jdbc.update("INSERT INTO basic_comparison_profiles (user_id, version, profile) VALUES (?, 1, ?::jsonb)",
                USER, json.writeValueAsString(BasicComparisonFixtures.b()));
        completeProfile();
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
        profile(BasicComparisonFixtures.singleParent());
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
        profile(new EligibilityProfile(FamilyDetailsState.NOT_STARTED, Map.of(
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

    @Test
    void 여러_공고를_한번에_조회하면_요청_순서대로_단건_조회와_같은_결과를_반환한다() throws Exception {
        long structured = announcement();
        publish(structured, saveDraft(structured, null, 0, EligibilityFixtures.d01()))
                .andExpect(status().isOk());
        long unstructured = announcement();
        profile(new EligibilityProfile(FamilyDetailsState.NOT_STARTED, Map.of(
                ProfileField.BIRTH_DATE, InputValue.provided("1997-01-10"),
                ProfileField.FAMILY_TYPE, InputValue.provided("UNMARRIED"))));
        var member = user(Long.toString(USER)).roles("USER");

        var batch = response(mvc.perform(get(BATCH).param("announcementIds", unstructured + "," + structured)
                        .param("region", "41463").with(member))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")));

        assertThat(batch.size()).isEqualTo(2);
        assertThat(batch.get(0).path("requestedAnnouncementId").asLong()).isEqualTo(unstructured);
        assertThat(batch.get(1).path("eligibleOptionIdsInRegion").get(0).asString()).isEqualTo("giheung-youth");
        assertThat(batch.get(0)).isEqualTo(response(mvc.perform(get(path(unstructured)).param("region", "41463")
                .with(member)).andExpect(status().isOk())));
        assertThat(batch.get(1)).isEqualTo(response(mvc.perform(get(path(structured)).param("region", "41463")
                .with(member)).andExpect(status().isOk())));
    }

    @Test
    void 여러_공고_조회는_정정된_공고와_중복_ID도_단건_조회와_같은_결과를_반환한다() throws Exception {
        long original = announcement();
        long correction = announcement();
        publish(correction, saveDraft(correction, null, 0, EligibilityFixtures.d01())).andExpect(status().isOk());
        long other = announcement();
        publish(other, saveDraft(other, null, 0, EligibilityFixtures.d05())).andExpect(status().isOk());
        jdbc.update("UPDATE announcements SET previous_announcement_id = ?, status = 'CORRECTION' WHERE id = ?",
                original, correction);
        profile(new EligibilityProfile(FamilyDetailsState.NOT_STARTED, Map.of(
                ProfileField.BIRTH_DATE, InputValue.provided("1997-01-10"),
                ProfileField.FAMILY_TYPE, InputValue.provided("UNMARRIED"))));
        for (int index = 0; index < 4; index++) {
            worker.runOnce(com.toadzip.backend.eligibility.domain.EligibilityWork.Kind.NOTICE);
        }
        var member = user(Long.toString(USER)).roles("USER");
        List<Long> requested = List.of(original, correction, original, other);

        var batch = response(mvc.perform(get(BATCH).param("announcementIds",
                        requested.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")))
                .with(member)).andExpect(status().isOk()));

        assertThat(batch.size()).isEqualTo(4);
        assertThat(batch.get(0).path("requestedAnnouncementId").asLong()).isEqualTo(original);
        assertThat(batch.get(0).path("announcementId").asLong()).isEqualTo(correction);
        assertThat(batch.get(0).path("content").isObject()).isTrue();
        for (int index = 0; index < requested.size(); index++) {
            assertThat(batch.get(index)).isEqualTo(response(mvc.perform(get(path(requested.get(index))).with(member))
                    .andExpect(status().isOk())));
        }
    }

    @Test
    void 여러_공고_조회에도_회원인증과_개수_지역_검증을_적용한다() throws Exception {
        long announcement = announcement();
        var member = user(Long.toString(USER)).roles("USER");
        mvc.perform(get(BATCH).param("announcementIds", Long.toString(announcement)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(BATCH).param("announcementIds", Long.toString(announcement))
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get(BATCH).param("announcementIds", "").with(member)).andExpect(status().isBadRequest());
        String tooMany = java.util.stream.LongStream.rangeClosed(1, 101).mapToObj(Long::toString)
                .collect(java.util.stream.Collectors.joining(","));
        mvc.perform(get(BATCH).param("announcementIds", tooMany).with(member)).andExpect(status().isBadRequest());
        mvc.perform(get(BATCH).param("announcementIds", announcement + "," + Long.MAX_VALUE).with(member))
                .andExpect(status().isNotFound());
        mvc.perform(get(BATCH).param("announcementIds", Long.toString(announcement)).param("region", "unknown")
                        .with(member))
                .andExpect(status().isBadRequest());
    }

    private static final String BATCH = "/api/v2/eligibility/announcements";

    private String path(long announcement) {
        return "/api/v2/announcements/" + announcement + "/eligibility";
    }

    private void profile(EligibilityProfile profile) {
        jdbc.update("INSERT INTO basic_comparison_profiles (user_id, version, profile) VALUES (?, 1, ?::jsonb)",
                USER, json.writeValueAsString(profile));
        completeProfile();
    }

    private void completeProfile() {
        assertThat(worker.runOnce(com.toadzip.backend.eligibility.domain.EligibilityWork.Kind.PROFILE)).isTrue();
    }

}
