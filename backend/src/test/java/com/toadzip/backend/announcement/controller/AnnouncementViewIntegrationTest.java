package com.toadzip.backend.announcement.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import jakarta.persistence.EntityManager;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AnnouncementViewIntegrationTest.ClockConfiguration.class)
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
class AnnouncementViewIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ViewClock clock;
    @Autowired private PrivacyNoticeCatalog notices;
    private long memberId;

    private final List<Long> announcementIds = new ArrayList<>();
    private long announcementId;

    @BeforeEach
    void setUp() {
        clock.set("2026-09-28T14:59:59Z");
        announcementId = createAnnouncement();
        memberId = jdbc.queryForObject("""
                INSERT INTO users(login_identifier,created_at) VALUES (?,CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, "view-" + UUID.randomUUID());
        jdbc.update("""
                INSERT INTO analytics_consents(id,user_id,decision,revision,notice_version,scope_version,
                    decided_at,expires_at,created_at,updated_at)
                VALUES (?,?,'GRANTED',1,?,?,?, ?,?,?)
                """, UUID.randomUUID(), memberId, notices.currentVersion("ANALYTICS_NOTICE"),
                notices.requiredAnalyticsScope(), java.sql.Timestamp.from(clock.instant()),
                java.sql.Timestamp.from(clock.instant().plusSeconds(86400 * 10)),
                java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM analytics_consents WHERE user_id = ?", memberId);
        jdbc.update("DELETE FROM users WHERE id = ?", memberId);
        for (long id : announcementIds) {
            // The view table's foreign key cascades on deletion.
            jdbc.update("DELETE FROM announcements WHERE id = ?", id);
        }
    }

    @Test
    void 같은_브라우저의_재방문은_한국_날짜별로_한번만_집계한다() throws Exception {
        String viewer = UUID.randomUUID().toString();
        record(announcementId, viewer).andExpect(status().isOk()).andExpect(jsonPath("$.data.viewCount").value(1));
        record(announcementId, viewer).andExpect(status().isOk()).andExpect(jsonPath("$.data.viewCount").value(1));
        assertEquals(1, count());
        clock.set("2026-09-28T15:00:00Z");
        record(announcementId, viewer).andExpect(status().isOk()).andExpect(jsonPath("$.data.viewCount").value(2));
        record(announcementId, viewer).andExpect(status().isOk()).andExpect(jsonPath("$.data.viewCount").value(2));
        assertEquals(2, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "UNSET", "DENIED", "WITHDRAWN", "EXPIRED", "OLD_SCOPE"})
    void 유효한_현재_범위_허용이_없으면_조회수를_저장하지_않는다(String state) throws Exception {
        switch (state) {
            case "MISSING" -> jdbc.update("DELETE FROM analytics_consents WHERE user_id = ?", memberId);
            case "EXPIRED" -> jdbc.update("UPDATE analytics_consents SET expires_at = ? WHERE user_id = ?",
                    java.sql.Timestamp.from(clock.instant()), memberId);
            case "OLD_SCOPE" -> jdbc.update("""
                    UPDATE analytics_consents SET notice_version = 'analytics-2026-10-09-v1',
                        scope_version = 'analytics-scope-1' WHERE user_id = ?
                    """, memberId);
            default -> jdbc.update("UPDATE analytics_consents SET decision = ? WHERE user_id = ?", state, memberId);
        }

        record(announcementId, UUID.randomUUID().toString()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ANALYTICS_CONSENT_REQUIRED"));
        assertEquals(0, count());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM announcement_views WHERE announcement_id = ?",
                Integer.class, announcementId));
    }

    @Test
    void 다른_브라우저와_다른_공고는_각각_집계한다() throws Exception {
        String first = UUID.randomUUID().toString();
        record(announcementId, first).andExpect(status().isOk());
        record(announcementId, UUID.randomUUID().toString()).andExpect(status().isOk());
        long other = createAnnouncement();
        record(other, first).andExpect(status().isOk()).andExpect(jsonPath("$.data.viewCount").value(1));
        assertEquals(2, count());
    }

    @Test
    void 같은_브라우저의_동시_요청은_한번만_증가한다() throws Exception {
        String viewer = UUID.randomUUID().toString();
        concurrently(() -> record(announcementId, viewer).andExpect(status().isOk()));
        assertEquals(1, count());
    }

    @Test
    void 다른_브라우저의_동시_증가는_유실되지_않는다() throws Exception {
        concurrently(() -> record(announcementId, UUID.randomUUID().toString()).andExpect(status().isOk()));
        assertEquals(12, count());
    }

    @Test
    void 읽기_요청은_집계하지_않고_상세와_목록은_저장된_조회수를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/announcements/{id}", announcementId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/announcements")).andExpect(status().isOk());
        assertEquals(0, count());
        record(announcementId, UUID.randomUUID().toString()).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/announcements/{id}", announcementId))
                .andExpect(jsonPath("$.data.viewCount").value(1));
        mockMvc.perform(get("/api/v1/announcements").param("keyword", "view-test-" + announcementId))
                .andExpect(jsonPath("$.data.items[0].viewCount").value(1));
        assertEquals(1, count());
    }

    @Test
    void 잘못된_식별자와_CSRF_없는_요청은_집계하지_않는다() throws Exception {
        for (String viewer : List.of("", "not-a-uuid", "1-1-1-1-1")) {
            record(announcementId, viewer).andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/v1/announcements/{id}/views", announcementId)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/announcements/{id}/views", announcementId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
        assertEquals(0, count());
    }

    @Test
    void 없는_공고와_삭제된_공고는_집계하지_않는다() throws Exception {
        String viewer = UUID.randomUUID().toString();
        record(Long.MAX_VALUE, viewer).andExpect(status().isNotFound());
        jdbc.update("UPDATE announcements SET admin_deleted = true WHERE id = ?", announcementId);
        record(announcementId, viewer).andExpect(status().isNotFound());
        assertEquals(0, count());
    }

    @Test
    void 대문자_UUID도_같은_브라우저로_집계한다() throws Exception {
        String viewer = "abcdefab-cdef-4abc-8def-abcdefabcdef";
        record(announcementId, viewer).andExpect(status().isOk());
        record(announcementId, viewer.toUpperCase(java.util.Locale.ROOT)).andExpect(status().isOk());
        assertEquals(1, count());
    }

    @Test
    void 이전_조회수를_가진_엔티티_수정이_새_조회수를_덮어쓰지_않는다() {
        transactions.executeWithoutResult(transaction -> {
            Announcement stale = entityManager.find(Announcement.class, announcementId);
            jdbc.update("UPDATE announcements SET view_count = view_count + 1 WHERE id = ?", announcementId);
            stale.moveToTrash();
            entityManager.flush();
        });
        assertEquals(1, count());
    }

    @Test
    void 카운터_갱신이_실패하면_중복방지_기록도_롤백되어_재시도할_수_있다() throws Exception {
        String viewer = UUID.randomUUID().toString();
        jdbc.update("UPDATE announcements SET view_count = ? WHERE id = ?", Long.MAX_VALUE, announcementId);
        record(announcementId, viewer).andExpect(status().isInternalServerError());
        jdbc.update("UPDATE announcements SET view_count = 0 WHERE id = ?", announcementId);
        record(announcementId, viewer).andExpect(status().isOk()).andExpect(jsonPath("$.data.viewCount").value(1));
        assertEquals(1, count());
    }

    private ResultActions record(long id, String viewer) throws Exception {
        return mockMvc.perform(post("/api/v1/announcements/{id}/views", id)
                .with(user(Long.toString(memberId)).roles("USER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"viewerId\":\"" + viewer + "\"}"));
    }

    private long count() {
        return jdbc.queryForObject("SELECT view_count FROM announcements WHERE id = ?", Long.class, announcementId);
    }

    private void concurrently(Callable<?> request) throws Exception {
        try (var executor = Executors.newFixedThreadPool(12)) {
            CountDownLatch ready = new CountDownLatch(12);
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> results = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 요청 시작 시간 초과");
                    }
                    return request.call();
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 요청 준비 시간 초과");
            }
            start.countDown();
            for (var result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        }
    }

    private long createAnnouncement() {
        Long id = transactions.execute(transaction -> {
            Announcement announcement = Announcement.create(
                    UUID.randomUUID().toString(), null, null, "조회 테스트",
                    AnnouncementPublicationType.ORIGINAL, RentalType.HAPPY_HOUSING, RecruitmentType.NEW,
                    AgencyCode.LH, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29),
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10),
                    "https://example.com/notice", null, 0, null, null, null);
            entityManager.persist(announcement);
            return announcement.getId();
        });
        jdbc.update("UPDATE announcements SET name = ? WHERE id = ?", "view-test-" + id, id);
        announcementIds.add(id);
        return id;
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        ViewClock viewClock() {
            return new ViewClock();
        }
    }

    static class ViewClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(Instant.EPOCH);

        void set(String value) {
            instant.set(Instant.parse(value));
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return instant.get(); }
    }
}
