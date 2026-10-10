package com.toadzip.backend.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import com.toadzip.backend.privacy.repository.UserRegistrationNoticeRepository;
import com.toadzip.backend.user.domain.SocialAuthorizationContext;
import com.toadzip.backend.user.domain.User;
import com.toadzip.backend.user.repository.UserRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserPrivacyLifecycleIntegrationTest {

    @Autowired private SocialUserService socialUsers;
    @Autowired private UserRepository users;
    @Autowired private UserRegistrationNoticeRepository registrationNotices;
    @Autowired private PrivacyNoticeCatalog notices;
    @Autowired private Clock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    @Test
    void 가입_고지는_별도_테이블에_한번만_저장하고_회원_이메일_갱신은_유지한다() {
        String subject = UUID.randomUUID().toString();
        Long id = socialUsers.findOrCreate("google", subject, "first@example.com", authorization());
        Timestamp recordedAt = jdbc.queryForObject(
                "SELECT recorded_at FROM privacy_registration_notices WHERE user_id = ?", Timestamp.class, id);
        Long repeated = socialUsers.findOrCreate("google", subject, "updated@example.com", authorization());
        registrationNotices.record(id, "must-not-replace-original", clock.instant().plusSeconds(10));

        assertEquals(id, repeated);
        assertEquals("updated@example.com", users.findById(id).orElseThrow().getEmail());
        assertEquals(notices.currentVersion("PRIVACY_POLICY"), jdbc.queryForObject(
                "SELECT policy_version FROM privacy_registration_notices WHERE user_id = ?", String.class, id));
        assertEquals(recordedAt, jdbc.queryForObject(
                "SELECT recorded_at FROM privacy_registration_notices WHERE user_id = ?", Timestamp.class, id));
        assertNull(jdbc.queryForObject(
                "SELECT purge_after FROM privacy_registration_notices WHERE user_id = ?", Timestamp.class, id));
    }

    @Test
    void 기존_회원은_로그인해도_가입_고지를_소급_생성하지_않는다() {
        String subject = UUID.randomUUID().toString();
        User legacy = User.create("google:" + subject, LocalDateTime.now(clock).minusYears(1));
        legacy.updateEmail("legacy@example.com");
        long userId = users.saveAndFlush(legacy).getId();

        assertEquals(userId, socialUsers.findOrCreate("google", subject, null, authorization()));
        assertEquals("legacy@example.com", users.findById(userId).orElseThrow().getEmail());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM privacy_registration_notices WHERE user_id = ?", Long.class, userId));
    }

    @Test
    void 개인정보_도입은_관리자_회원_삭제_API를_제공하지_않는다() throws Exception {
        long userId = createUser();
        var before = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", userId);

        mvc.perform(delete("/api/admin/users/" + userId).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isMethodNotAllowed());

        assertEquals(before, jdbc.queryForMap("SELECT * FROM users WHERE id = ?", userId));
    }

    @Test
    void 동시_최초_로그인은_하나의_회원과_가입고지를_생성한다() throws Exception {
        String subject = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return socialUsers.findOrCreate("google", subject, "member@example.com", authorization());
            });
            var second = executor.submit(() -> {
                start.await();
                return socialUsers.findOrCreate("google", subject, "member@example.com", authorization());
            });
            start.countDown();
            long userId = first.get(10, TimeUnit.SECONDS);
            assertEquals(userId, second.get(10, TimeUnit.SECONDS));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT count(*) FROM privacy_registration_notices WHERE user_id = ?", Long.class, userId));
        }
    }

    @Test
    void 만료된_인가는_회원과_가입고지를_생성하지_않는다() {
        String subject = UUID.randomUUID().toString();
        var expired = new SocialAuthorizationContext(notices.currentVersion("PRIVACY_POLICY"),
                clock.instant().minus(Duration.ofMinutes(10)));

        assertThrows(IllegalArgumentException.class,
                () -> socialUsers.findOrCreate("google", subject, null, expired));
        assertTrue(users.findByLoginIdentifier("google:" + subject).isEmpty());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", "analytics-2026-10-09-v2"})
    void 확인되지_않은_정책으로_가입해도_최신_고지를_임의_기록하지_않는다(String version) {
        Long id = socialUsers.findOrCreate("google", UUID.randomUUID().toString(), "member@example.com",
                new SocialAuthorizationContext(version, clock.instant()));

        assertEquals("member@example.com", users.findById(id).orElseThrow().getEmail());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM privacy_registration_notices WHERE user_id = ?", Long.class, id));
    }

    @Test
    void 구버전_안내로_가입하면_전달된_보관_버전을_그대로_기록한다() {
        String oldVersion = "privacy-2026-10-09-v1";
        Long id = socialUsers.findOrCreate("google", UUID.randomUUID().toString(), "member@example.com",
                new SocialAuthorizationContext(oldVersion, clock.instant()));

        assertEquals(oldVersion, jdbc.queryForObject(
                "SELECT policy_version FROM privacy_registration_notices WHERE user_id = ?", String.class, id));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", "privacy-2026-10-09-v1"})
    void 기존_회원은_정책_확인없이_로그인하고_가입고지를_소급하지_않는다(String version) {
        String subject = UUID.randomUUID().toString();
        User existing = User.create("google:" + subject, LocalDateTime.now(clock).minusYears(1));
        long id = users.saveAndFlush(existing).getId();

        assertEquals(id, socialUsers.findOrCreate("google", subject, "updated@example.com",
                new SocialAuthorizationContext(version, clock.instant())));
        assertEquals("updated@example.com", users.findById(id).orElseThrow().getEmail());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM privacy_registration_notices WHERE user_id = ?", Long.class, id));
    }

    private long createUser() {
        return socialUsers.findOrCreate("google", UUID.randomUUID().toString(), "member@example.com", authorization());
    }

    private SocialAuthorizationContext authorization() {
        return new SocialAuthorizationContext(notices.currentVersion("PRIVACY_POLICY"), clock.instant());
    }
}
