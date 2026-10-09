package com.toadzip.backend.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.interest.domain.FavoriteAnnouncement;
import com.toadzip.backend.interest.domain.FavoriteHousingComplex;
import com.toadzip.backend.privacy.domain.PrivacyHash;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import com.toadzip.backend.user.domain.SocialAuthorizationContext;
import com.toadzip.backend.user.domain.User;
import com.toadzip.backend.user.exception.SocialAuthorizationRejectedException;
import com.toadzip.backend.user.repository.UserLifecycleRepository;
import com.toadzip.backend.user.repository.UserRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserPrivacyLifecycleIntegrationTest {

    @Autowired private SocialUserService socialUsers;
    @Autowired private UserDeletionService deletions;
    @Autowired private UserRepository users;
    @Autowired private UserLifecycleRepository lifecycle;
    @Autowired private PrivacyNoticeCatalog notices;
    @Autowired private Clock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mvc;
    @Autowired private EntityManager entityManager;

    @Test
    void 회원_생성_정책은_유지하고_공급자_이메일은_계속_갱신한다() {
        String subject = UUID.randomUUID().toString();
        Long id = socialUsers.findOrCreate("google", subject, "first@example.com", authorization());
        Long repeated = socialUsers.findOrCreate("google", subject, "updated@example.com", authorization());
        User saved = users.findById(id).orElseThrow();
        assertEquals(id, repeated);
        assertEquals("updated@example.com", saved.getEmail());
        assertEquals(notices.currentVersion("PRIVACY_POLICY"), saved.getRegistrationPolicyVersion());
    }

    @Test
    void 탈퇴는_이메일과_모든_연결_행을_지우고_반복_요청에도_204다() throws Exception {
        long userId = createUser();
        UUID consentId = seedPersonalData(userId);
        long complexId = jdbc.queryForObject(
                "SELECT housing_complex_id FROM favorite_housing_complexes WHERE user_id = ?", Long.class, userId);
        long announcementId = jdbc.queryForObject(
                "SELECT announcement_id FROM favorite_announcements WHERE user_id = ?", Long.class, userId);
        mvc.perform(delete("/api/admin/users/" + userId).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isNoContent());
        assertFalse(users.existsById(userId));
        for (String table : java.util.List.of("notification_subscriptions", "notification_email_preferences",
                "notification_interest_events", "analytics_consents", "user_places", "user_eligibility_infos",
                "favorite_regions", "favorite_announcements", "favorite_housing_complexes")) {
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?",
                    Long.class, userId));
        }
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM analytics_consent_events WHERE consent_id = ?",
                Long.class, consentId));
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM housing_complexes WHERE id = ?",
                Long.class, complexId));
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM announcements WHERE id = ?",
                Long.class, announcementId));
        mvc.perform(delete("/api/admin/users/" + userId).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void 탈퇴에는_관리자_권한과_CSRF가_필요하다() throws Exception {
        long userId = createUser();
        mvc.perform(delete("/api/admin/users/" + userId).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/admin/users/" + userId).with(user(Long.toString(userId)).roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/users/" + userId).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        assertTrue(users.existsById(userId));
    }

    @Test
    void 탈퇴한_회원의_잔존_세션은_공개_요청에서도_익명으로_전환하지_않고_차단한다() throws Exception {
        long userId = createUser();
        deletions.delete(userId);
        for (String endpoint : java.util.List.of("/api/auth/me", "/api/v1/privacy/analytics-context",
                "/api/v1/notification-subscriptions/me", "/api/health")) {
            MockHttpSession session = memberSession(userId);
            mvc.perform(get(endpoint).session(session)).andExpect(status().isUnauthorized());
            assertTrue(session.isInvalid());
        }
    }

    @Test
    void 삭제_중_기다린_기존_OAuth는_재생성을_못하고_새_로그인만_가입한다() throws Exception {
        String subject = UUID.randomUUID().toString();
        SocialAuthorizationContext oldAuthorization = authorization();
        long id = socialUsers.findOrCreate("google", subject, "member@example.com", oldAuthorization);
        CountDownLatch deletedWithinTransaction = new CountDownLatch(1);
        CountDownLatch commitDeletion = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deletion = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                deletions.delete(id);
                deletedWithinTransaction.countDown();
                await(commitDeletion);
                return true;
            }));
            assertTrue(deletedWithinTransaction.await(5, TimeUnit.SECONDS));
            var callback = executor.submit(() -> socialUsers.findOrCreate(
                    "google", subject, "member@example.com", oldAuthorization));
            commitDeletion.countDown();
            assertTrue(deletion.get(5, TimeUnit.SECONDS));
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> callback.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof SocialAuthorizationRejectedException,
                    () -> failure.getCause().getClass().getName());
        } finally {
            commitDeletion.countDown();
        }
        assertTrue(users.findByLoginIdentifier("google:" + subject).isEmpty());
        long newId = socialUsers.findOrCreate("google", subject, "member@example.com", authorization());
        assertNotEquals(id, newId);
    }

    @Test
    void 사용자_삭제_실패는_이전_자식_삭제와_표식까지_롤백한다() {
        long id = createUser();
        seedPersonalData(id);
        jdbc.execute("CREATE TABLE deletion_failure_guard (user_id bigint REFERENCES users(id))");
        try {
            jdbc.update("INSERT INTO deletion_failure_guard VALUES (?)", id);
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> deletions.delete(id));
            assertTrue(users.existsById(id));
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM analytics_consents WHERE user_id = ?",
                    Long.class, id));
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM notification_subscriptions WHERE user_id = ?",
                    Long.class, id));
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM user_deletion_markers WHERE login_identifier_hash = ?",
                    Long.class, PrivacyHash.sha256(users.findById(id).orElseThrow().getLoginIdentifier())));
        } finally {
            jdbc.execute("DROP TABLE deletion_failure_guard");
        }
    }

    @Test
    void 탈퇴_표식은_만료_경계부터_500행_청크로_실제_삭제되고_재실행해도_안전하다() {
        Instant now = Instant.parse("2026-10-09T00:00:00Z");
        for (int index = 0; index < 501; index++) {
            lifecycle.recordDeletion("expired:" + index, now.minusSeconds(600), now);
        }
        lifecycle.recordDeletion("still-valid", now.minusSeconds(599), now.plusSeconds(1));
        var meters = new SimpleMeterRegistry();
        var service = new UserDeletionRetentionService(lifecycle, transactionManager,
                Clock.fixed(now, ZoneOffset.UTC), meters);
        service.purge();
        service.purge();
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM user_deletion_markers WHERE expires_at <= ?",
                Long.class, Timestamp.from(now)));
        assertEquals(1L, jdbc.queryForObject(
                    "SELECT count(*) FROM user_deletion_markers WHERE login_identifier_hash = ?",
                Long.class, PrivacyHash.sha256("still-valid")));
        assertEquals(501, meters.get("privacy.retention.deleted.total").tag("job", "user-deletion").counter().count());
    }

    @Test
    void 오래된_로그인은_삭제_표식이_이미_없어도_재가입할_수_없다() {
        var expired = new SocialAuthorizationContext(notices.currentVersion("PRIVACY_POLICY"),
                clock.instant().minus(Duration.ofMinutes(10)));
        assertThrows(IllegalArgumentException.class,
                () -> socialUsers.findOrCreate("google", UUID.randomUUID().toString(), null, expired));
    }

    private long createUser() {
        return socialUsers.findOrCreate("google", UUID.randomUUID().toString(), "member@example.com", authorization());
    }

    private SocialAuthorizationContext authorization() {
        return new SocialAuthorizationContext(notices.currentVersion("PRIVACY_POLICY"), clock.instant());
    }

    private MockHttpSession memberSession(long userId) {
        var session = new MockHttpSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(Long.toString(userId), null,
                AuthorityUtils.createAuthorityList("ROLE_USER")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private UUID seedPersonalData(long userId) {
        UUID consentId = UUID.randomUUID();
        seedFavorites(userId);
        jdbc.update("""
                INSERT INTO analytics_consents(id, user_id, decision, revision, created_at, updated_at,
                    decided_at, notice_version, scope_version)
                VALUES (?, ?, 'DENIED', 1, now(), now(), now(), 'notice-test', 'scope-test')
                """, consentId, userId);
        jdbc.update("""
                INSERT INTO analytics_consent_events(id, consent_id, command_id, request_fingerprint,
                    previous_decision, decision, previous_revision, revision, notice_version, scope_version,
                    source, recorded_at)
                VALUES (?, ?, ?, ?, 'UNSET', 'DENIED', 0, 1, 'notice-test', 'scope-test', 'SETTINGS', now())
                """, UUID.randomUUID(), consentId, UUID.randomUUID(), "a".repeat(64));
        jdbc.update("""
                INSERT INTO notification_interest_events(event_id, user_id, event_type, source, target_type,
                    target_id, created_at, request_fingerprint, outcome, notice_version, settings_revision)
                VALUES (?, ?, 'CONFIRMED', 'COMPLEX_DETAIL', 'COMPLEX', '42', now(), ?, 'ACTIVATED', 'notice-test', 1)
                """, UUID.randomUUID(), userId, "b".repeat(64));
        jdbc.update("""
                INSERT INTO notification_subscriptions(user_id, target_type, target_id, active, updated_at,
                    requested_at, expires_at, purge_after, notice_version)
                VALUES (?, 'COMPLEX', '42', true, now(), now(), now() + interval '1 year',
                    now() + interval '1 year 90 days', 'notice-test')
                """, userId);
        jdbc.update("INSERT INTO notification_email_preferences(user_id, email, updated_at) VALUES (?, ?, now())",
                userId, "alert@example.com");
        jdbc.update("""
                INSERT INTO user_places(user_id, name, address, latitude, longitude, created_at)
                VALUES (?, 'fixture', 'fixture', 37.0, 127.0, now())
                """, userId);
        jdbc.update("""
                INSERT INTO favorite_regions(user_id, province_code, city_county_district_code, created_at)
                VALUES (?, '11', '110', now())
                """, userId);
        jdbc.update("""
                INSERT INTO user_eligibility_infos(user_id, birth_date, current_residence_region,
                    head_of_household, household_member_count, single_parent_family, non_homeowner, marital_status,
                    child_count, student, employed, personal_average_monthly_income, household_average_monthly_income,
                    parents_average_monthly_income, total_assets, vehicle_value, housing_subscription_account,
                    housing_benefit_recipient, basic_living_recipient, disabled, veteran)
                VALUES (?, '2000-01-01', 'fixture', true, 1, false, true, 'SINGLE', 0, false, true,
                    100, 100, 100, 100, 100, false, false, false, false, false)
                """, userId);
        return consentId;
    }

    private void seedFavorites(long userId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User user = entityManager.getReference(User.class, userId);
            HousingComplex complex = HousingComplex.create("공개 단지", UUID.randomUUID().toString(), "행복주택",
                    Address.create("공개 주소", "1114010100100010000", "1114010100", "11", "11140",
                            new BigDecimal("37.5665"), new BigDecimal("126.9780")),
                    100, "LH", null, null, null, null, null, 100, null, null);
            entityManager.persist(complex);
            LocalDate date = LocalDate.of(2026, 10, 9);
            Announcement announcement = Announcement.create(UUID.randomUUID().toString(), null, null, "공개 공고",
                    AnnouncementPublicationType.ORIGINAL, RentalType.HAPPY_HOUSING, RecruitmentType.NEW,
                    AgencyCode.LH, date, date.plusDays(1), date.plusDays(2), date.plusDays(3),
                    "https://example.com/notice", null, 0, null, null, null);
            entityManager.persist(announcement);
            LocalDateTime createdAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            entityManager.persist(FavoriteHousingComplex.create(user, complex, createdAt));
            entityManager.persist(FavoriteAnnouncement.create(user, announcement, createdAt));
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test coordination timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
