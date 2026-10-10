package com.toadzip.backend.privacy.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.privacy.service.UserRegistrationRetentionService;
import com.toadzip.backend.user.domain.User;
import com.toadzip.backend.user.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserRegistrationRetentionIntegrationTest {

    @Autowired private UserRepository users;
    @Autowired private UserRegistrationNoticeRepository registrationNotices;
    @Autowired private Clock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void 가입_고지_배치는_기존_회원과_자식_자료를_유지하고_고아_고지만_500행_청크로_파기한다() {
        Instant now = Instant.parse("2026-10-09T00:00:00Z");
        long userId = createUser();
        jdbc.update("""
                INSERT INTO user_places(user_id, name, address, latitude, longitude, created_at)
                VALUES (?, 'fixture', 'fixture', 37.0, 127.0, now())
                """, userId);
        var userBefore = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", userId);
        var placesBefore = jdbc.queryForList("SELECT * FROM user_places WHERE user_id = ?", userId);
        for (int index = 1; index <= 501; index++) {
            registrationNotices.record(-index, "orphan-test", now.minusSeconds(60));
        }
        var meters = new SimpleMeterRegistry();
        var service = new UserRegistrationRetentionService(registrationNotices, transactionManager,
                Clock.fixed(now, ZoneOffset.UTC), meters);

        service.purge();
        service.purge();

        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM privacy_registration_notices WHERE user_id < 0", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM privacy_registration_notices WHERE user_id = ?", Long.class, userId));
        assertEquals(userBefore, jdbc.queryForMap("SELECT * FROM users WHERE id = ?", userId));
        assertEquals(placesBefore, jdbc.queryForList("SELECT * FROM user_places WHERE user_id = ?", userId));
        assertEquals(501, meters.get("privacy.retention.deleted.total")
                .tag("job", "registration-notice").counter().count());
    }

    @Test
    void 가입_고지_파기_시각_경계는_고아에게만_적용한다() {
        Instant now = clock.instant();
        long userId = createUser();
        registrationNotices.record(-601, "orphan-test", now.minusSeconds(60));
        registrationNotices.record(-602, "orphan-test", now.minusSeconds(60));
        jdbc.update("UPDATE privacy_registration_notices SET purge_after = ? WHERE user_id IN (?, -601)",
                Timestamp.from(now), userId);
        jdbc.update("UPDATE privacy_registration_notices SET purge_after = ? WHERE user_id = -602",
                Timestamp.from(now.plusSeconds(1)));

        assertEquals(1, registrationNotices.purgeOrphans(now, 500));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM privacy_registration_notices WHERE user_id = -601", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM privacy_registration_notices WHERE user_id = -602", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM privacy_registration_notices WHERE user_id = ?", Long.class, userId));
        assertTrue(users.existsById(userId));
        registrationNotices.purgeOrphans(now.plusSeconds(1), 500);
    }

    private long createUser() {
        User member = User.create("privacy-retention:" + UUID.randomUUID(), LocalDateTime.now(clock));
        member.updateEmail("retained@example.invalid");
        long userId = users.saveAndFlush(member).getId();
        registrationNotices.record(userId, "fixture-policy", clock.instant());
        return userId;
    }
}
