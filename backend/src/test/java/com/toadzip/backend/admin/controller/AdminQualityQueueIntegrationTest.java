package com.toadzip.backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@org.springframework.test.context.jdbc.Sql("/db/migration/V20261016_01__quality_comparison_workflow.sql")
class AdminQualityQueueIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private ObjectMapper json;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    @Test
    void 공고와_원천_식별자_기관_연결단지로_검토대상을_찾고_제외한_연결은_검색하지_않는다() throws Exception {
        var fixture = new NotificationFixtures(entities, jdbc);
        var announcement = fixture.announcement();
        var complex = fixture.complex("11140", "1114010100");
        fixture.link(announcement, complex);
        String id = announcement.getId().toString();
        String complexId = complex.getId().toString();
        assertThat(read(Map.of("announcementId", id, "complexId", complexId, "provider", "LH"))
                .path("items").get(0).path("id").asLong()).isEqualTo(announcement.getId());
        assertThat(read(Map.of("announcementId", id, "provider", "SH")).path("totalElements").asLong()).isZero();
        assertThat(read(Map.of("sourceIdentifier", announcement.getSourceAnnouncementIdentifier()))
                .path("totalElements").asLong()).isEqualTo(1);
        jdbc.update("UPDATE announcements SET provider='한국토지주택공사' WHERE id=?", announcement.getId());
        assertThat(read(Map.of("announcementId", id, "provider", "LH")).path("totalElements").asLong()).isEqualTo(1);
        jdbc.update("UPDATE supply_rows SET admin_excluded=true WHERE announcement_id=?", announcement.getId());
        assertThat(read(Map.of("announcementId", id, "complexId", complexId)).path("totalElements").asLong()).isZero();
    }

    @Test
    void 모집상태는_확인한_일정을_우선하고_취소공고를_접수중으로_분류하지_않는다() throws Exception {
        var announcement = new NotificationFixtures(entities, jdbc).announcement();
        String id = announcement.getId().toString();
        LocalDate today = LocalDate.now(clock);
        jdbc.update("UPDATE announcements SET application_start_date=?, application_end_date=? WHERE id=?",
                today.minusDays(1), today.plusDays(1), announcement.getId());
        assertThat(read(Map.of("announcementId", id, "applicationStatus", "APPLYING"))
                .path("totalElements").asLong()).isEqualTo(1);
        entities.persist(AnnouncementApplicationSchedule.verified(announcement, null, null,
                ApplicationScheduleState.CONDITIONAL, "잔여 세대 확인 후 접수", today, today.plusDays(2),
                null, null, "https://example.com/notice.pdf", 1));
        entities.flush();
        jdbc.update("UPDATE announcements SET application_schedule_reviewed=true WHERE id=?", announcement.getId());
        assertThat(read(Map.of("announcementId", id, "applicationStatus", "CONDITIONAL"))
                .path("totalElements").asLong()).isEqualTo(1);
        assertThat(read(Map.of("announcementId", id, "applicationStatus", "APPLYING"))
                .path("totalElements").asLong()).isZero();
        jdbc.update("UPDATE announcements SET status='취소공고' WHERE id=?", announcement.getId());
        assertThat(read(Map.of("announcementId", id, "applicationStatus", "CANCELLED"))
                .path("totalElements").asLong()).isEqualTo(1);
    }

    private JsonNode read(Map<String, String> filters) throws Exception {
        var request = get("/api/admin/quality/queue").with(user("admin").roles("ADMIN"));
        filters.forEach(request::param);
        return json.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }

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
        for (var filter : Map.of("announcementId", "0", "complexId", "-1", "provider", "OTHER",
                "applicationStatus", "UNKNOWN").entrySet()) {
            mvc.perform(get("/api/admin/quality/queue").param(filter.getKey(), filter.getValue())
                    .with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        }
    }
}
