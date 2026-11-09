package com.toadzip.backend.admin.controller;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineExecution;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.user.domain.User;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminOperationsControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entities;
    @Autowired private JdbcClient jdbc;
    @Autowired private Clock clock;

    @Test
    void 관리자는_빈_단지_집계를_실제_0건으로_조회한다() throws Exception {
        mockMvc.perform(get("/api/admin/operations/complexes").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.recruiting").value(0))
                .andExpect(jsonPath("$.data.agencies.LH").value(0))
                .andExpect(jsonPath("$.data.asOf").exists());
    }

    @Test
    void 관리자가_아니면_운영_집계를_조회할_수_없다() throws Exception {
        mockMvc.perform(get("/api/admin/operations/complexes").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 가입_집계는_지원하지_않는_기간을_거부한다() throws Exception {
        mockMvc.perform(get("/api/admin/operations/users").param("days", "8")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }
    @Test
    void 여러_공고가_같은_단지에_연결돼도_모집중_단지는_한번만_센다() throws Exception {
        var complex = complex("LH");
        complex("SH");
        var deleted = complex("GH");
        var first = announcement("첫 공고");
        var second = announcement("두번째 공고");
        supply(first, complex);
        supply(first, complex);
        supply(second, complex);
        entities.flush();
        jdbc.sql("UPDATE housing_complexes SET admin_deleted = true WHERE id = :id")
                .param("id", deleted.getId()).update();
        jdbc.sql("UPDATE housing_complexes SET source_review_required = true WHERE id = :id")
                .param("id", complex.getId()).update();

        mockMvc.perform(get("/api/admin/operations/complexes").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.recruiting").value(1))
                .andExpect(jsonPath("$.data.reviewRequired").value(1))
                .andExpect(jsonPath("$.data.agencies.LH").value(1))
                .andExpect(jsonPath("$.data.agencies.SH").value(1))
                .andExpect(jsonPath("$.data.agencies.GH").value(0));
    }

    @Test
    void 확인_필요_사유가_겹쳐도_전체는_공고를_중복_제거한다() throws Exception {
        var unconfirmed = Announcement.createUnconfirmed("preview-unconfirmed", "미확정", AgencyCode.SH,
                LocalDate.now(clock), "https://example.com/notice");
        entities.persist(unconfirmed);
        announcement("공급정보 없는 공고");
        entities.flush();

        mockMvc.perform(get("/api/admin/operations/announcements").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attention").value(2))
                .andExpect(jsonPath("$.data.reasons.SCHEDULE_MISSING").value(1))
                .andExpect(jsonPath("$.data.reasons.SUPPLY_MISSING").value(2))
                .andExpect(jsonPath("$.data.reasons.UNCONFIRMED").value(1))
                .andExpect(jsonPath("$.data.statuses.UNKNOWN").value(1))
                .andExpect(jsonPath("$.data.statuses.APPLYING").value(1));
    }

    @Test
    void 운영홈_필터는_중복없이_관리목록에_적용된다() throws Exception {
        var complex = complex("LH");
        var notice = announcement("모집 공고");
        supply(notice, complex);
        supply(notice, complex);
        entities.persist(Announcement.createUnconfirmed("filter-unconfirmed", "미확정", AgencyCode.SH,
                LocalDate.now(clock), "https://example.com/notice"));
        entities.flush();
        mockMvc.perform(get("/api/admin/housing-complexes").param("recruiting", "true")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(get("/api/admin/announcements").param("applicationStatus", "APPLYING")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].announcement.applicationStatus").value("APPLYING"));
        mockMvc.perform(get("/api/admin/announcements").param("attention", "ANY")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].announcement.attentionReasons.length()").value(3));
    }

    @Test
    void 모든_공고상태와_확인사유_필터가_집계와_일치한다() throws Exception {
        var complex = complex("LH");
        for (int index = 0; index < 5; index++) {
            var notice = announcement("상태 " + index);
            supply(notice, complex);
            entities.flush();
            if (index == 1) {
                jdbc.sql("UPDATE announcements SET application_start_date = :day, application_end_date = :day "
                        + "WHERE id = :id").param("day", LocalDate.now(clock).plusDays(2))
                        .param("id", notice.getId()).update();
            }
            if (index == 2) {
                jdbc.sql("UPDATE announcements SET application_start_date = :day, application_end_date = :day "
                        + "WHERE id = :id").param("day", LocalDate.now(clock).minusDays(2))
                        .param("id", notice.getId()).update();
            }
            if (index == 3) {
                jdbc.sql("UPDATE announcements SET status = 'CANCELLATION' WHERE id = :id")
                        .param("id", notice.getId()).update();
            }
            if (index == 4) {
                entities.persist(com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule.verified(
                        notice, null, null,
                        com.toadzip.backend.announcement.domain.ApplicationScheduleState.CONDITIONAL,
                        "잔여 세대", LocalDate.now(clock), LocalDate.now(clock).plusDays(1), null, null,
                        "https://example.com/notice", null));
                entities.flush();
                jdbc.sql("UPDATE announcements SET application_schedule_reviewed = true WHERE id = :id")
                        .param("id", notice.getId()).update();
            }
        }
        entities.persist(Announcement.createUnconfirmed("filter-overlap", "미확정", AgencyCode.SH,
                LocalDate.now(clock), "https://example.com/notice"));
        entities.flush();
        entities.clear();
        String overview = mockMvc.perform(get("/api/admin/operations/announcements")
                .with(user("admin").roles("ADMIN"))).andReturn().getResponse().getContentAsString();
        for (String state : java.util.List.of("BEFORE_APPLICATION", "APPLYING", "CLOSED", "CONDITIONAL",
                "CANCELLED", "UNKNOWN")) {
            int expected = com.jayway.jsonpath.JsonPath.read(overview, "$.data.statuses." + state);
            mockMvc.perform(get("/api/admin/announcements").param("applicationStatus", state)
                    .with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(expected));
        }
        for (String reason : java.util.List.of("ANY", "SCHEDULE_MISSING", "SUPPLY_MISSING", "UNCONFIRMED")) {
            String path = "$.data.reasons." + reason;
            if (reason.equals("ANY")) { path = "$.data.attention"; }
            int expected = com.jayway.jsonpath.JsonPath.read(overview, path);
            mockMvc.perform(get("/api/admin/announcements").param("attention", reason)
                    .with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(expected));
        }
        mockMvc.perform(get("/api/admin/announcements").param("applicationStatus", "INVALID")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
    }

    @Test
    void 단지필터는_대체공고와_삭제를_제외하고_페이지_전에_적용한다() throws Exception {
        var replaced = complex("LH");
        var old = announcement("이전 공고");
        supply(old, replaced);
        var successor = announcement("후속 공고");
        entities.flush();
        jdbc.sql("UPDATE announcements SET previous_announcement_id = :old, application_start_date = :day, "
                + "application_end_date = :day WHERE id = :id").param("old", old.getId())
                .param("day", LocalDate.now(clock).minusDays(2)).param("id", successor.getId()).update();
        for (int index = 0; index < 3; index++) {
            var complex = complex("SH");
            var notice = announcement("진행 공고 " + index);
            supply(notice, complex);
            entities.flush();
        }
        var deleted = complex("SH");
        entities.flush();
        jdbc.sql("UPDATE housing_complexes SET admin_deleted = true WHERE id = :id")
                .param("id", deleted.getId()).update();
        mockMvc.perform(get("/api/admin/operations/complexes").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data.recruiting").value(3))
                .andExpect(jsonPath("$.data.missingCoordinates").value(0));
        mockMvc.perform(get("/api/admin/housing-complexes").param("recruiting", "true")
                .param("provider", "SH").param("size", "2").param("page", "1")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    void 좌표필수_스키마에서는_좌표미확인_목록이_0건이다() throws Exception {
        complex("LH");
        entities.flush();
        mockMvc.perform(get("/api/admin/housing-complexes").param("missingCoordinates", "true")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 한국_시각_가입_기간_경계를_지키고_기간_밖_가입은_추이에_섞지_않는다() throws Exception {
        LocalDate today = clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        LocalDate start = today.minusDays(6);
        entities.persist(User.create("google:current", start.atStartOfDay()));
        entities.persist(User.create("google:previous", start.atStartOfDay().minusSeconds(1)));
        entities.persist(User.create("google:old", start.minusDays(7).atStartOfDay().minusSeconds(1)));
        entities.persist(User.create("google:future", today.plusDays(1).atStartOfDay()));
        entities.flush();

        mockMvc.perform(get("/api/admin/operations/users").param("days", "7")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.newUsers").value(1))
                .andExpect(jsonPath("$.data.previousNewUsers").value(1))
                .andExpect(jsonPath("$.data.trend.length()").value(7))
                .andExpect(jsonPath("$.data.trend[0].day").value(start.toString()))
                .andExpect(jsonPath("$.data.trend[0].count").value(1))
                .andExpect(jsonPath("$.data.trend[6].count").value(0));
    }

    @Test
    void 최근_실패가_마지막_성공_시각을_덮어쓰지_않고_미실행_기관은_빈값으로_표시한다() throws Exception {
        execution("COMPLETED", "2026-11-06T01:00:00Z");
        execution("FAILED", "2026-11-07T01:00:00Z");

        mockMvc.perform(get("/api/admin/operations/collections").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources[?(@.agency=='LH')].lastSuccessAt")
                        .value(org.hamcrest.Matchers.contains("2026-11-06T01:00:00Z")))
                .andExpect(jsonPath("$.data.sources[?(@.agency=='LH')].latestStatus")
                        .value(org.hamcrest.Matchers.contains("FAILED")))
                .andExpect(jsonPath("$.data.sources[?(@.agency=='SH')].lastSuccessAt")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data.sources[?(@.agency=='SH')].latestStatus")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void 늦게_저장된_과거_실행이_최근_실행을_덮어쓰지_않는다() throws Exception {
        execution("COMPLETED", "2026-11-06T01:00:00Z");
        execution("FAILED", "2026-11-05T01:00:00Z");

        mockMvc.perform(get("/api/admin/operations/collections").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources[?(@.agency=='LH')].latestStatus")
                        .value(org.hamcrest.Matchers.contains("COMPLETED")))
                .andExpect(jsonPath("$.data.sources[?(@.agency=='LH')].lastSuccessAt")
                        .value(org.hamcrest.Matchers.contains("2026-11-06T01:00:00Z")));
    }

    private HousingComplex complex(String provider) {
        var address = Address.create("테스트 도로 1", null, "1111010100", "11", "11110",
                new BigDecimal("37.5"), new BigDecimal("127.0"));
        var complex = HousingComplex.create("테스트 단지", UUID.randomUUID().toString(), "HAPPY_HOUSING", address,
                10, provider, null, null, null, null, null, null, null, null);
        entities.persist(complex);
        return complex;
    }

    private Announcement announcement(String name) {
        LocalDate today = LocalDate.now(clock);
        var announcement = Announcement.create(UUID.randomUUID().toString(), null, null, name,
                AnnouncementPublicationType.ORIGINAL, RentalType.HAPPY_HOUSING, RecruitmentType.NEW,
                AgencyCode.LH, today, today.minusDays(1), today.plusDays(1), today.plusDays(2),
                "https://example.com/notice", null, 0, null);
        entities.persist(announcement);
        return announcement;
    }

    private void supply(Announcement announcement, HousingComplex complex) {
        entities.persist(SupplyRow.create(announcement, complex, null, UUID.randomUUID().toString(), 0,
                "단지", "주택형", null, null, SupplyCategory.NEW_SUPPLY, null, null));
    }

    private void execution(String status, String at) {
        var execution = DataPipelineExecution.start(UUID.randomUUID(), DataPipelineType.ANNOUNCEMENT_SYNC,
                Instant.parse(at));
        entities.persist(execution);
        entities.flush();
        jdbc.sql("UPDATE data_pipeline_executions SET status = :status, finished_at = :at WHERE id = :id")
                .param("status", status).param("at", Instant.parse(at).atOffset(java.time.ZoneOffset.UTC))
                .param("id", execution.getId()).update();
    }

}
