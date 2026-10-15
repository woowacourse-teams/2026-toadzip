package com.toadzip.backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminQualityQueueIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private ObjectMapper json;

    @Test
    void 누락_항목과_미검토_사유를_표시하고_휴지통과_검색_밖의_대상은_제외한다() throws Exception {
        String name = "검토목록-" + UUID.randomUUID();
        var complex = complexes.saveAndFlush(HousingComplex.create(name, "manual:" + UUID.randomUUID(),
                "HAPPY_HOUSING", Address.create("서울특별시 중구 세종대로 110", "1114010300100010000",
                        "1114010300", "11", "11140", new BigDecimal("37.5665"), new BigDecimal("126.978")),
                0, "LH", null, null, null, null, null, 0, null, null));
        var response = mvc.perform(get("/api/admin/quality/queue").param("keyword", name)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var items = json.readTree(response).get("data").get("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).get("reasons").toString()).contains("MISSING", "UNREVIEWED");
        assertThat(items.get(0).get("missingFields").toString()).contains("준공일", "엘리베이터");
        assertThat(items.get(0).get("missingFields").toString()).doesNotContain("세대수", "주차대수");
        complex.moveToTrash();
        complexes.flush();
        var after = mvc.perform(get("/api/admin/quality/queue").param("keyword", name)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(after).get("data").get("totalElements").asLong()).isZero();
    }

    @Test
    void 관리자만_접근하고_허용된_필터와_페이지_범위만_받는다() throws Exception {
        mvc.perform(get("/api/admin/quality/queue")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/quality/queue").param("reason", "ARBITRARY")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/quality/queue").param("page", "-1")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
    }
}
