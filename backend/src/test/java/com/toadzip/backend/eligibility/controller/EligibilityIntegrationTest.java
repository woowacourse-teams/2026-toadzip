package com.toadzip.backend.eligibility.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.eligibility.DongtanFixtures;
import com.toadzip.backend.eligibility.ProfileFixtures;
import com.toadzip.backend.eligibility.SacheonFixtures;
import com.toadzip.backend.eligibility.domain.InputFact;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.SaveProfile;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class EligibilityIntegrationTest extends EligibilityIntegrationSupport {
    private static final String PROFILE = "/api/v1/me/eligibility-profile";

    @Test
    void 로그인과_관리자_권한과_CSRF를_실제_보안_필터로_검증한다() throws Exception {
        mvc.perform(get(PROFILE)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/announcements/1/eligibility"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PROFILE).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get(admin(1)).with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put(PROFILE)
                                .with(user(Long.toString(USER)).roles("USER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(new SaveProfile(0L, Map.of()))))
                .andExpect(status().isForbidden());
        mvc.perform(get(PROFILE).with(user("999999999").roles("USER")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 입력_카탈로그는_파생_의존항목을_JSON_배열로_반환한다() throws Exception {
        mvc.perform(
                        get("/api/v1/eligibility/input-catalog")
                                .with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facts.length()").value(45))
                .andExpect(
                        jsonPath("$.data.facts[?(@.code == 'AGE_AT_REFERENCE')].factKind")
                                .value(org.hamcrest.Matchers.contains("DERIVED")))
                .andExpect(
                        jsonPath("$.data.facts[?(@.code == 'AGE_AT_REFERENCE')].dependsOn[0]")
                                .value(org.hamcrest.Matchers.contains("BIRTH_DATE")))
                .andExpect(jsonPath("$.data.inputGaps[0].neededFact").isString())
                .andExpect(jsonPath("$.data.inputGaps.length()").value(22));
    }

    @Test
    void 프로필_상태와_특수항목을_함께_저장하고_버전_충돌과_알수없는_사실을_거부한다() throws Exception {
        mvc.perform(get(PROFILE).with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.facts.HOUSEHOLD_ASSETS.state").value("UNKNOWN"));
        saveProfile(0, ProfileFixtures.c().facts())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.facts.NEAR_POVERTY.value").value(true))
                .andExpect(
                        jsonPath("$.data.facts.HOUSEHOLD_CAR_VALUE.state").value("NOT_APPLICABLE"));
        saveProfile(0, ProfileFixtures.b().facts())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ELIGIBILITY_CONFLICT"));
        mvc.perform(
                        put(PROFILE)
                                .with(user(Long.toString(USER)).roles("USER"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"expectedVersion\":1,\"facts\":{\"IS_HOUSEHOLD_HEAD\":{\"state\":\"PROVIDED\",\"value\":true}}}"))
                .andExpect(status().isBadRequest());
        long other = USER + 1;
        jdbc.update(
                "INSERT INTO users(id, login_identifier, created_at) VALUES (?, ?, now())",
                other,
                "other-fixture");
        mvc.perform(get(PROFILE).with(user(Long.toString(other)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    void 초안은_평가에서_제외하고_B의_자산_수정마다_현재_공개본으로_상세를_재계산한다() throws Exception {
        long announcement = announcement();
        JsonNode draft = saveDraft(announcement, null, 0, DongtanFixtures.content());
        detail(announcement).andExpect(jsonPath("$.data.availability").value("NOT_STRUCTURED"));
        publish(announcement, draft)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        saveProfile(0, ProfileFixtures.b().facts()).andExpect(status().isOk());
        detail(announcement)
                .andExpect(jsonPath("$.data.result.summary").value("UNDETERMINED_ONLY"))
                .andExpect(jsonPath("$.data.profileVersion").value(1))
                .andExpect(
                        jsonPath("$.data.result.options[0].missingFactCodes[0]")
                                .value("HOUSEHOLD_ASSETS"));
        saveProfile(
                        1,
                        ProfileFixtures.with(
                                        ProfileFixtures.b(), InputFact.HOUSEHOLD_ASSETS, 379000000)
                                .facts())
                .andExpect(status().isOk());
        detail(announcement)
                .andExpect(jsonPath("$.data.result.summary").value("HAS_CONFIRMATION_ONLY"));
        saveProfile(
                        2,
                        ProfileFixtures.with(
                                        ProfileFixtures.b(), InputFact.HOUSEHOLD_ASSETS, 379000001)
                                .facts())
                .andExpect(status().isOk());
        detail(announcement).andExpect(jsonPath("$.data.result.summary").value("NONE_ELIGIBLE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM announcement_rule_sets", Long.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM eligibility_profiles WHERE user_id = ?",
                                Long.class,
                                USER))
                .isEqualTo(1);
    }

    @Test
    void 정정_체인의_마지막_게시물만_평가하고_새_판본_공개시_이전_공개본을_지운다() throws Exception {
        long original = announcement();
        publish(original, saveDraft(original, null, 0, DongtanFixtures.content()))
                .andExpect(status().isOk());
        long corrected = announcement();
        jdbc.update(
                "UPDATE announcements SET previous_announcement_id = ?, status = '정정공고' WHERE id ="
                        + " ?",
                original,
                corrected);
        detail(original)
                .andExpect(jsonPath("$.data.announcementId").value(corrected))
                .andExpect(jsonPath("$.data.availability").value("NOT_STRUCTURED"));
        publish(corrected, saveDraft(corrected, null, 0, SacheonFixtures.content()))
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM announcement_rule_sets WHERE announcement_id"
                                        + " = ?",
                                Long.class,
                                original))
                .isZero();
        detail(original).andExpect(jsonPath("$.data.availability").value("AVAILABLE"));
        long cancellation = announcement();
        jdbc.update(
                "UPDATE announcements SET previous_announcement_id = ?, status = '취소공고' WHERE id ="
                        + " ?",
                corrected,
                cancellation);
        detail(original)
                .andExpect(jsonPath("$.data.availability").value("CANCELLED"))
                .andExpect(jsonPath("$.data.result").isEmpty());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 같은_프로필_버전의_경합은_한_요청만_저장하고_일반값과_특수값을_섞지_않는다() throws Exception {
        try {
            saveProfile(0, Map.of()).andExpect(status().isOk());
            CyclicBarrier barrier = new CyclicBarrier(2);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var actions =
                        List.of(ProfileFixtures.b(), ProfileFixtures.c()).stream()
                                .map(
                                        profile ->
                                                executor.submit(
                                                        () -> {
                                                            barrier.await(5, TimeUnit.SECONDS);
                                                            return saveProfile(1, profile.facts())
                                                                    .andReturn()
                                                                    .getResponse()
                                                                    .getStatus();
                                                        }))
                                .toList();
                assertThat(
                                List.of(
                                        actions.get(0).get(20, TimeUnit.SECONDS),
                                        actions.get(1).get(20, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(200, 409);
            }
            JsonNode profile =
                    response(
                            mvc.perform(get(PROFILE).with(user(Long.toString(USER)).roles("USER")))
                                    .andExpect(status().isOk())
                                    .andExpect(jsonPath("$.data.version").value(2)));
            boolean nearPoverty =
                    profile.path("facts").path("NEAR_POVERTY").path("value").asBoolean();
            long householdIncome =
                    profile.path("facts").path("HOUSEHOLD_MONTHLY_INCOME").path("value").asLong();
            if (nearPoverty) {
                assertThat(householdIncome).isEqualTo(600000);
            }
            if (!nearPoverty) {
                assertThat(householdIncome).isEqualTo(9500000);
            }
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?", USER);
        }
    }

    private org.springframework.test.web.servlet.ResultActions saveProfile(
            long version, Map<InputFact, com.toadzip.backend.eligibility.domain.InputValue> facts)
            throws Exception {
        return mvc.perform(
                put(PROFILE)
                        .with(user(Long.toString(USER)).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new SaveProfile(version, facts))));
    }

    private org.springframework.test.web.servlet.ResultActions detail(long announcement)
            throws Exception {
        return mvc.perform(
                        get("/api/v1/announcements/" + announcement + "/eligibility")
                                .with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk());
    }
}
