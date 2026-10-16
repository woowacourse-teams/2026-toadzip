package com.toadzip.backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.admin.domain.AdminAccount;
import com.toadzip.backend.admin.repository.AdminAccountRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql({"/db/migration/V20261016_01__quality_comparison_workflow.sql",
        "/db/migration/V20261016_04__quality_review_tasks.sql"})
@Import(ReviewTaskIntegrationTest.FixedClock.class)
class ReviewTaskIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private AdminAccountRepository admins;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcClient jdbc;

    @BeforeEach
    void admins() {
        for (String name : java.util.List.of("task-first", "task-second")) {
            admins.saveAndFlush(AdminAccount.create(name, "test-only-hash", LocalDateTime.of(2026, 10, 16, 0, 0)));
        }
    }

    @Test
    void 실제_관리자에게_배정하고_revision으로_동시_수정을_막는다() throws Exception {
        long id = complex();
        save(id, 0, "task-first", "2026-10-16", "IN_PROGRESS", 200);
        var current = read(root(id), "task-second");
        assertThat(current.path("assignee").asText()).isEqualTo("task-first");
        assertThat(current.path("revision").asLong()).isEqualTo(1);
        save(id, 1, "task-second", "2026-10-17", "ON_HOLD", 200);
        save(id, 1, "task-first", "2026-10-16", "DONE", 409);
        assertThat(read(root(id), "task-first").path("assignee").asText()).isEqualTo("task-second");
        assertThat(jdbc.sql("SELECT count(*) FROM admin_data_changes WHERE resource_id = :id "
                + "AND action = 'UPDATE_REVIEW_TASK'").param("id", id).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void 한국_날짜로_기한초과를_판정하고_내_업무_및_완료_상태를_구분한다() throws Exception {
        long late = complex(); long today = complex(); long other = complex();
        save(late, 0, "task-first", "2026-10-16", "OPEN", 200);
        save(today, 0, "task-first", "2026-10-17", "OPEN", 200);
        save(other, 0, "task-second", "2026-10-16", "OPEN", 200);
        var alerts = read("/api/admin/quality/tasks/alerts", "task-first");
        assertThat(alerts.path("totalElements").asLong()).isEqualTo(1);
        assertThat(alerts.path("items").get(0).path("id").asLong()).isEqualTo(late);
        assertThat(read(root(today), "task-first").path("overdue").asBoolean()).isFalse();
        save(late, 1, "task-first", "2026-10-16", "DONE", 200);
        assertThat(read("/api/admin/quality/tasks/alerts", "task-first").path("totalElements").asLong()).isZero();
        assertThat(complexes.findById(late).orElseThrow().getTotalHouseholdCount()).isEqualTo(120);
    }

    @Test
    void 확인한_원천이_소실되면_기한과_별개로_내_담당_알림에_나타난다() throws Exception {
        long id = complex();
        save(id, 0, "task-first", "2026-10-20", "OPEN", 200);
        jdbc.sql("""
                INSERT INTO admin_comparison_checks(resource, resource_id, target, evidence,
                    checked_values, evidence_note, actor)
                VALUES ('COMPLEX', :id, 'basic', CAST(:evidence AS jsonb), '{}'::jsonb, '이전 원천 확인', 'task-first')
                """).param("id", id).param("evidence", """
                    [{"category":"MYHOME_COMPLEX","sourceKey":"removed-source","rows":[{"hsmp_nm":"이전 원천"}]}]
                    """).update();
        var alerts = read("/api/admin/quality/tasks/alerts", "task-first");
        assertThat(alerts.path("totalElements").asLong()).isEqualTo(1);
        assertThat(alerts.path("items").get(0).path("sourceChanged").asBoolean()).isTrue();
        assertThat(alerts.path("items").get(0).path("overdue").asBoolean()).isFalse();
    }

    @Test
    void 없는_담당자와_상태_및_권한없는_변경을_거부한다() throws Exception {
        long id = complex();
        save(id, 0, "missing-admin", "2026-10-16", "OPEN", 400);
        save(id, 0, "task-first", "2026-10-16", "WRONG", 400);
        save(id, 9, "task-first", "2026-10-16", "OPEN", 409);
        mvc.perform(put(root(id)).with(user("task-first").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"assignee":"task-first","dueDate":null,"status":"OPEN","note":"버전 없는 요청"}
                        """)).andExpect(status().isBadRequest());
        mvc.perform(get(root(id)).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get(root(id))).andExpect(status().isUnauthorized());
        mvc.perform(put(root(id)).with(user("task-first").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        var names = read("/api/admin/quality/tasks/assignees", "task-first");
        assertThat(names.toString()).contains("task-first", "task-second").doesNotContain("test-only-hash");
    }

    private JsonNode read(String path, String actor) throws Exception {
        return json.readTree(mvc.perform(get(path).with(user(actor).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    private void save(long id, long revision, String assignee, String due, String state,
            int expected) throws Exception {
        mvc.perform(put(root(id)).with(user("task-first").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "expectedRevision", revision, "assignee", assignee, "dueDate", due, "status", state,
                        "note", "공식 원천 재검토 담당 배정")))).andExpect(status().is(expected));
    }

    private String root(long id) {
        return "/api/admin/quality/tasks/COMPLEX/" + id;
    }

    private long complex() {
        return complexes.saveAndFlush(HousingComplex.createFromMyHome("담당 업무", "manual:" + UUID.randomUUID(),
                "HAPPY_HOUSING", Address.create("서울 중구 세종대로 110", "1114010300100010000", "1114010300",
                        "11", "11140", new BigDecimal("37.5665"), new BigDecimal("126.978")),
                120, "LH", null, null, null, null, null, 0)).getId();
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock taskClock() {
            return Clock.fixed(Instant.parse("2026-10-16T15:30:00Z"), ZoneOffset.UTC);
        }
    }
}
