package com.toadzip.backend.basiccomparison.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class ResidenceProfileIntegrationTest extends ComparisonIntegrationSupport {
    private static final String PROFILE = "/api/v2/me/eligibility-profile";

    @Test
    void 지역_거주_완료개월과_미래_계약종료일을_공통_입력으로_저장한다() throws Exception {
        save("24", "PROVIDED", "2030-12-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.profile.facts.CURRENT_REGION_RESIDENCE_MONTHS.value").value(24))
                .andExpect(jsonPath("$.data.profile.facts.CURRENT_LEASE_END_DATE.value").value("2030-12-31"))
                .andExpect(jsonPath("$.data.profile.facts.HOUSEHOLD_MONTHLY_INCOME.state").value("UNKNOWN"));
        mvc.perform(get(PROFILE).with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inputContractVersion").value(3))
                .andExpect(jsonPath("$.data.profile.facts.CURRENT_REGION_RESIDENCE_MONTHS.value").value(24));
    }

    @Test
    void 거주기간_0과_계약종료일_해당없음을_미입력과_구분한다() throws Exception {
        save("0", "NOT_APPLICABLE", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.facts.CURRENT_REGION_RESIDENCE_MONTHS.value").value(0))
                .andExpect(jsonPath("$.data.profile.facts.CURRENT_LEASE_END_DATE.state").value("NOT_APPLICABLE"));
    }

    @Test
    void 음수_소수_또는_과도한_거주개월을_거절한다() throws Exception {
        for (String months : java.util.List.of("-1", "1.5", "1801")) {
            save(months, "UNKNOWN", null).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM basic_comparison_profiles WHERE user_id = ?", Long.class, USER)).isZero();
    }

    @Test
    void 기존_프로필에_새_거주정보를_추정해_채우지_않는다() throws Exception {
        jdbc.update("INSERT INTO basic_comparison_profiles (user_id, version, input_contract_version, profile)"
                + " VALUES (?, 1, 2, '{\"familyDetailsState\":\"NOT_STARTED\",\"facts\":{}}'::jsonb)", USER);
        mvc.perform(get(PROFILE).with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.facts.CURRENT_REGION_RESIDENCE_MONTHS.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.profile.facts.CURRENT_LEASE_END_DATE.state").value("UNKNOWN"));
    }

    private ResultActions save(String months, String leaseState, String leaseDate) throws Exception {
        String leaseValue = "null";
        if (leaseDate != null) {
            leaseValue = "\"" + leaseDate + "\"";
        }
        String request = """
                {"expectedVersion":0,"inputContractVersion":3,"profile":{"familyDetailsState":"NOT_STARTED",
                 "facts":{"CURRENT_REGION_RESIDENCE_MONTHS":{"state":"PROVIDED","value":%s},
                 "CURRENT_LEASE_END_DATE":{"state":"%s","value":%s}}}}
                """.formatted(months, leaseState, leaseValue);
        return mvc.perform(put(PROFILE).with(user(Long.toString(USER)).roles("USER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request));
    }
}
