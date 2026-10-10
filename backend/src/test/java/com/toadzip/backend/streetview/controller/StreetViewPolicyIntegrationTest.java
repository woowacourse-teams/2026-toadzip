package com.toadzip.backend.streetview.controller;

import static com.toadzip.backend.streetview.StreetViewFixtures.complex;
import static com.toadzip.backend.streetview.StreetViewFixtures.policy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StreetViewPolicyIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private com.toadzip.backend.user.repository.UserRepository privacyPermissionUsers;

    private String privacyPermissionMemberId() {
        var member = com.toadzip.backend.user.domain.User.create(
                "google:permission-" + java.util.UUID.randomUUID(), java.time.LocalDateTime.now());
        return privacyPermissionUsers.saveAndFlush(member).getId().toString();
    }

    private static final String POLICY = "/api/admin/street-view-policy";
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private jakarta.persistence.EntityManager entityManager;
    private long complexId;

    @BeforeEach
    void setUp() {
        policy(jdbc, true);
        complexId = complex(jdbc);
    }

    @Test
    void 공개_API는_출입구_좌표와_기본_시야만_반환한다() throws Exception {
        mockMvc.perform(get(endpoint(complexId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.complexId").value(complexId))
                .andExpect(jsonPath("$.data.provider").value("NAVER"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.policyRevision").value(0))
                .andExpect(jsonPath("$.data.initialization.searchPosition.latitude").value(37.561443))
                .andExpect(jsonPath("$.data.initialization.searchPosition.longitude").value(126.962715))
                .andExpect(jsonPath("$.data.initialization.lookAtPosition.latitude").value(37.561443))
                .andExpect(jsonPath("$.data.initialization.lookAtPosition.longitude").value(126.962715))
                .andExpect(jsonPath("$.data.initialization.tilt").value(0))
                .andExpect(jsonPath("$.data.initialization.fov").value(90))
                .andExpect(jsonPath("$.data.initialization.pan").doesNotExist())
                .andExpect(jsonPath("$.data.reason").doesNotExist())
                .andExpect(jsonPath("$.data.updatedBy").doesNotExist())
                .andExpect(jsonPath("$.data.updatedAt").doesNotExist());
        long another = complex(jdbc);
        mockMvc.perform(get(endpoint(another))).andExpect(jsonPath("$.data.complexId").value(another));
    }

    @Test
    void alias를_해석하고_없는_단지와_삭제한_단지를_제외한다() throws Exception {
        long aliasId = 900_000_000L;
        jdbc.sql("INSERT INTO housing_complex_aliases (id, housing_complex_id, merge_id) VALUES (:id, :target, :merge)")
                .param("id", aliasId).param("target", complexId).param("merge", UUID.randomUUID()).update();
        mockMvc.perform(get(endpoint(aliasId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.complexId").value(complexId));
        mockMvc.perform(get(endpoint(Long.MAX_VALUE))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMPLEX_NOT_FOUND"));
        jdbc.sql("UPDATE housing_complexes SET admin_deleted = true WHERE id = :id").param("id", complexId).update();
        mockMvc.perform(get(endpoint(aliasId))).andExpect(status().isNotFound());
        mockMvc.perform(get(endpoint(complexId))).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @CsvSource({"0,0", "90.000001,127", "37,-180.000001"})
    void 잘못된_좌표면_초기화_정보를_제공하지_않는다(String latitude, String longitude) throws Exception {
        jdbc.sql("UPDATE housing_complexes SET latitude = :latitude, longitude = :longitude WHERE id = :id")
                .param("latitude", decimal(latitude)).param("longitude", decimal(longitude))
                .param("id", complexId).update();
        mockMvc.perform(get(endpoint(complexId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disabledReason").value("INVALID_COORDINATES"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.initialization").doesNotExist());
        jdbc.sql("UPDATE street_view_policies SET enabled = false").update();
        entityManager.clear();
        mockMvc.perform(get(endpoint(complexId)))
                .andExpect(jsonPath("$.data.disabledReason").value("POLICY_DISABLED"));
    }

    @Test
    void 실제_변경만_버전과_감사를_남기고_과거_버전은_거부한다() throws Exception {
        update(0, false, "  장애 확인  ").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.reason").value("장애 확인"))
                .andExpect(jsonPath("$.data.updatedBy").value("operator"));
        update(1, false, "장애 확인").andExpect(jsonPath("$.data.version").value(1));
        update(0, false, "장애 확인").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_DATA_CONFLICT"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        update(1, false, "원인 확인 중").andExpect(jsonPath("$.data.version").value(2));
        mockMvc.perform(get(POLICY + "/changes").with(user("operator").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].actor").value("operator"));
        assertThat(jdbc.sql("SELECT count(*) FROM admin_data_changes WHERE resource_type = 'STREET_VIEW_POLICY'")
                .query(Long.class).single()).isEqualTo(2);
        mockMvc.perform(get(POLICY + "/changes?page=-1").with(user("operator").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 사유는_앞뒤_공백을_제거한_뒤_길이를_검증한다() throws Exception {
        update(0, true, " " + "가".repeat(500) + " ").andExpect(status().isOk());
        update(1, true, "가".repeat(501)).andExpect(status().isBadRequest());
    }

    @Test
    void 정책_관리에는_관리자와_CSRF가_필요하다() throws Exception {
        mockMvc.perform(get(POLICY)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(POLICY).with(user(privacyPermissionMemberId()).roles("USER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."));
        mockMvc.perform(get(POLICY + "/changes")).andExpect(status().isUnauthorized());
        mockMvc.perform(put(POLICY).with(user("operator").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("요청 보안 토큰이 유효하지 않습니다."));
        update(0, true, "  ").andExpect(status().isBadRequest());
        update(-1, true, "활성화").andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions update(long version, boolean enabled, String reason)
            throws Exception {
        return mockMvc.perform(put(POLICY).with(user("operator").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"version":%d,"enabled":%s,"reason":"%s"}
                        """.formatted(version, enabled, reason)));
    }

    private String endpoint(long id) {
        return "/api/v1/complexes/" + id + "/street-view";
    }

    private java.math.BigDecimal decimal(String value) {
        if (value == null) {
            return null;
        }
        return new java.math.BigDecimal(value);
    }
}
