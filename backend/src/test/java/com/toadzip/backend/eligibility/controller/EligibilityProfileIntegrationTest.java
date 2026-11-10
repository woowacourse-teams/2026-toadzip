package com.toadzip.backend.eligibility.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.eligibility.BasicComparisonFixtures;
import com.toadzip.backend.eligibility.domain.EligibilityProfile;
import com.toadzip.backend.eligibility.domain.FamilyDetailsState;
import com.toadzip.backend.eligibility.domain.InputValue;
import com.toadzip.backend.eligibility.domain.ProfileField;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.SaveProfile;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.node.ObjectNode;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class EligibilityProfileIntegrationTest extends EligibilityIntegrationSupport {
    private static final String PROFILE = "/api/v2/me/eligibility-profile";

    @Test
    void 로그인_회원권한_CSRF와_삭제된_회원_경계를_유지한다() throws Exception {
        for (String path :
                List.of(
                        PROFILE,
                        "/api/v2/eligibility/input-catalog",
                        "/api/v2/eligibility/summary",
                        "/api/v2/announcements/1/basic-comparison")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("99999999").roles("USER")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(
                        put(PROFILE)
                                .with(user(Long.toString(USER)).roles("USER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                new SaveProfile(0L, 3, EligibilityProfile.empty()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 현재_입력만_카탈로그에_노출하고_가족_소득은_항상_입력으로_남긴다() throws Exception {
        var data =
                response(
                        mvc.perform(
                                        get("/api/v2/eligibility/input-catalog")
                                                .with(user(Long.toString(USER)).roles("USER")))
                                .andExpect(status().isOk())
                                .andExpect(header().string("Cache-Control", "no-store"))
                                .andExpect(jsonPath("$.data.inputContractVersion").value(3))
                                .andExpect(jsonPath("$.data.fields.length()").value(25))
                                .andExpect(
                                        jsonPath(
                                                        "$.data.fields[?(@.code =="
                                                            + " 'HOUSEHOLD_MONTHLY_INCOME')].optional")
                                                .value(org.hamcrest.Matchers.contains(false)))
                                .andExpect(
                                        jsonPath(
                                                        "$.data.fields[?(@.code =="
                                                            + " 'FAMILY_TOTAL_ASSETS')].optional")
                                                .value(org.hamcrest.Matchers.contains(true))));
        assertThat(data.toString())
                .doesNotContain(
                        "SUPPORTED_SINGLE_PARENT_FAMILY",
                        "IS_HOUSEHOLD_HEAD",
                        "SAVINGS_PAYMENT_COUNT",
                        "UNIVERSITY_REGION");
    }

    @Test
    void 현재값을_저장하고_가족정보_생략과_세대소득을_독립적으로_유지한다() throws Exception {
        mvc.perform(get(PROFILE).with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(
                        jsonPath("$.data.profile.facts.HOUSEHOLD_MONTHLY_INCOME.state")
                                .value("UNKNOWN"));
        save(USER, 0, BasicComparisonFixtures.b())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        save(USER, 1, BasicComparisonFixtures.skipped())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.profile.familyDetailsState").value("SKIPPED"))
                .andExpect(
                        jsonPath("$.data.profile.facts.FAMILY_TOTAL_ASSETS.state").value("UNKNOWN"))
                .andExpect(
                        jsonPath("$.data.profile.facts.HOUSEHOLD_MONTHLY_INCOME.value")
                                .value(3000000));
        save(USER, 1, BasicComparisonFixtures.b()).andExpect(status().isConflict());
        save(USER, 2, EligibilityProfile.empty()).andExpect(status().isOk());
        mvc.perform(get(PROFILE).with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(
                        jsonPath("$.data.profile.facts.HOUSEHOLD_MONTHLY_INCOME.state")
                                .value("UNKNOWN"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM basic_comparison_profiles WHERE user_id = ?",
                                Long.class,
                                USER))
                .isOne();
    }

    @Test
    void 다른_회원의_프로필과_섞지_않는다() throws Exception {
        long other = USER + 1;
        jdbc.update(
                "INSERT INTO users (id, login_identifier, created_at) VALUES (?,"
                        + " 'comparison-other', now())",
                other);
        save(USER, 0, BasicComparisonFixtures.b()).andExpect(status().isOk());
        mvc.perform(get(PROFILE).with(user(Long.toString(other)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(
                        jsonPath("$.data.profile.facts.HOUSEHOLD_MONTHLY_INCOME.state")
                                .value("UNKNOWN"));
        save(other, 0, BasicComparisonFixtures.skipped()).andExpect(status().isOk());
        mvc.perform(get(PROFILE).with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.familyDetailsState").value("PROVIDED"));
    }

    @Test
    void 제외된_신분과_임의_회원ID와_잘못된_계약버전은_받지_않는다() throws Exception {
        ObjectNode valid =
                (ObjectNode) json.valueToTree(new SaveProfile(0L, 3, EligibilityProfile.empty()));
        ObjectNode unsupported = valid.deepCopy();
        ((ObjectNode) unsupported.path("profile").path("facts"))
                .putObject("SUPPORTED_SINGLE_PARENT_FAMILY")
                .put("state", "PROVIDED")
                .put("value", true);
        raw(unsupported).andExpect(status().isBadRequest());
        raw(valid.deepCopy().put("userId", USER + 1)).andExpect(status().isBadRequest());
        raw(valid.deepCopy().put("inputContractVersion", 1)).andExpect(status().isBadRequest());
        raw(valid.deepCopy().put("expectedVersion", 0.5)).andExpect(status().isBadRequest());
        raw(valid.deepCopy().put("expectedVersion", "0")).andExpect(status().isBadRequest());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM basic_comparison_profiles WHERE user_id = ?",
                                Long.class,
                                USER))
                .isZero();
    }

    @Test
    void 본인_포함_가족_보유정보의_모순을_거부한다() throws Exception {
        save(
                        USER,
                        0,
                        BasicComparisonFixtures.with(
                                BasicComparisonFixtures.b(), ProfileField.APPLICANT_OWNS_HOUSE, true))
                .andExpect(status().isBadRequest());
        Map<ProfileField, InputValue> facts = new EnumMap<>(BasicComparisonFixtures.b().facts());
        facts.put(ProfileField.APPLICANT_OWNS_CAR, InputValue.provided(true));
        facts.put(ProfileField.APPLICANT_CAR_VALUE, InputValue.provided(20000000L));
        facts.put(ProfileField.FAMILY_OWNS_CAR, InputValue.provided(true));
        facts.put(ProfileField.FAMILY_CAR_VALUE, InputValue.provided(10000000L));
        save(USER, 0, new EligibilityProfile(FamilyDetailsState.PROVIDED, facts))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 자산_산정_내역을_추정해서_입력한_가족총액을_고치지_않는다() throws Exception {
        save(
                        USER,
                        0,
                        BasicComparisonFixtures.with(
                                BasicComparisonFixtures.b(),
                                ProfileField.FAMILY_TOTAL_ASSETS,
                                50000000L))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.profile.facts.FAMILY_TOTAL_ASSETS.value").value(50000000))
                .andExpect(jsonPath("$.data.profile.facts.APPLICANT_ASSETS.value").value(60000000));
    }

    @Test
    void 부모소득은_지정된_예외흐름에서만_저장한다() throws Exception {
        save(
                        USER,
                        0,
                        BasicComparisonFixtures.with(
                                BasicComparisonFixtures.b(),
                                ProfileField.PARENTS_MONTHLY_INCOME,
                                3000000L))
                .andExpect(status().isBadRequest());
        Map<ProfileField, InputValue> facts = new EnumMap<>(ProfileField.class);
        facts.put(ProfileField.FAMILY_TYPE, InputValue.provided("UNMARRIED"));
        facts.put(ProfileField.IS_UNIVERSITY_STUDENT, InputValue.provided(true));
        facts.put(ProfileField.IS_HOUSEHOLD_SEPARATED, InputValue.provided(true));
        facts.put(ProfileField.APPLICANT_MONTHLY_INCOME, InputValue.provided(0L));
        facts.put(ProfileField.PARENTS_MONTHLY_INCOME, InputValue.provided(3000000L));
        save(USER, 0, new EligibilityProfile(FamilyDetailsState.NOT_STARTED, facts))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.profile.facts.PARENTS_MONTHLY_INCOME.value")
                                .value(3000000));
    }

    @Test
    void 관리자_소득표_조회에는_소득_종류와_연도_금액_근거가_있다() throws Exception {
        String path = "/api/admin/basic-comparison/income-standards";
        mvc.perform(get(path).with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data[?(@.kind == 'STANDARD_MEDIAN_INCOME')].baseYear")
                                .value(org.hamcrest.Matchers.contains(2026)))
                .andExpect(
                        jsonPath("$.data[?(@.kind == 'STANDARD_MEDIAN_INCOME')].amounts.2")
                                .value(org.hamcrest.Matchers.contains(4199292)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 같은_프로필_버전의_동시수정은_전체_값_한벌만_저장한다() throws Exception {
        try {
            save(USER, 0, EligibilityProfile.empty()).andExpect(status().isOk());
            CyclicBarrier barrier = new CyclicBarrier(2);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first =
                        executor.submit(
                                () -> {
                                    barrier.await(5, TimeUnit.SECONDS);
                                    return save(USER, 1, BasicComparisonFixtures.b())
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                });
                var second =
                        executor.submit(
                                () -> {
                                    barrier.await(5, TimeUnit.SECONDS);
                                    return save(USER, 1, BasicComparisonFixtures.skipped())
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                });
                assertThat(
                                List.of(
                                        first.get(20, TimeUnit.SECONDS),
                                        second.get(20, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(200, 409);
            }
            var data =
                    response(
                            mvc.perform(get(PROFILE).with(user(Long.toString(USER)).roles("USER")))
                                    .andExpect(status().isOk())
                                    .andExpect(jsonPath("$.data.version").value(2)));
            EligibilityProfile saved =
                    json.treeToValue(data.path("profile"), EligibilityProfile.class);
            assertThat(saved).isIn(BasicComparisonFixtures.b(), BasicComparisonFixtures.skipped());
        } finally {
            jdbc.update("DELETE FROM basic_comparison_profiles WHERE user_id = ?", USER);
        }
    }

    private ResultActions save(long member, long version, EligibilityProfile profile)
            throws Exception {
        return mvc.perform(
                put(PROFILE)
                        .with(user(Long.toString(member)).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new SaveProfile(version, 3, profile))));
    }

    private ResultActions raw(ObjectNode request) throws Exception {
        return mvc.perform(
                put(PROFILE)
                        .with(user(Long.toString(USER)).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)));
    }
}
