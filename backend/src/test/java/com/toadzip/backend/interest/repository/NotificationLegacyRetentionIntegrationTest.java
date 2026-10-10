package com.toadzip.backend.interest.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.toadzip.backend.interest.service.NotificationSettingsService;
import com.toadzip.backend.privacy.repository.PrivacyNotificationRetentionRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NotificationLegacyRetentionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-09T09:00:00Z");
    private static final List<String> LEGACY_TABLES = List.of("users", "notification_subscriptions",
            "notification_email_preferences", "notification_guest_subscriptions",
            "notification_guest_email_preferences", "notification_guest_cancellation_requests",
            "notification_interest_events");

    @Autowired private PrivacyNotificationRetentionRepository retention;
    @Autowired private NotificationSettingsService settings;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void 신규_파기는_기존회원과_알림과_비회원과_과거분석자료를_그대로_보존한다() {
        long member = seedLegacyRecords();
        Map<String, List<String>> before = legacySnapshot();
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO privacy_notification_events(event_id,session_id,event_type,source,target_type,target_id,
                    created_at,request_fingerprint,outcome,purge_after)
                VALUES (?,?,'CLICKED','REGION_SEARCH','REGION','11',?,'test','OBSERVED',?)
                """, eventId, UUID.randomUUID(), Timestamp.from(NOW.minusSeconds(100)), Timestamp.from(NOW));
        jdbcTemplate.update("""
                INSERT INTO privacy_notification_notices(user_id,target_type,target_id,notice_version,
                    requested_at,expires_at,purge_after) VALUES (?,'REGION','11','test',?,?,?)
                """, member, Timestamp.from(NOW.minusSeconds(100)), Timestamp.from(NOW.minusSeconds(10)),
                Timestamp.from(NOW));

        assertEquals(0, retention.purgeChunk(NOW.minusNanos(1000), 500));
        assertEquals(2, retention.backlog(NOW).count());
        assertEquals(2, retention.purgeChunk(NOW, 500));
        assertEquals(0, retention.backlog(NOW).count());
        assertEquals(before, legacySnapshot());
    }

    @Test
    void 기존알림의_조회는_고지증빙을_만들거나_기존자료를_보완하지_않는다() {
        long member = seedLegacyRecords();
        jdbcTemplate.update("UPDATE notification_subscriptions SET expires_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().plusSeconds(86400)), member);
        Map<String, List<String>> before = legacySnapshot();

        var response = settings.current(member);

        assertEquals(0, response.settingsRevision());
        assertEquals(1, response.targets().size());
        assertNull(response.targets().getFirst().noticeVersion());
        assertNull(response.targets().getFirst().requestedAt());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM privacy_notification_notices WHERE user_id = ?", Integer.class, member));
        assertEquals(before, legacySnapshot());
    }

    @Test
    void 없는회원의_고아메타만_파기하고_존재회원_revision은_유지한다() {
        long member = seedLegacyRecords();
        Map<String, List<String>> before = legacySnapshot();
        jdbcTemplate.update("""
                INSERT INTO privacy_notification_states(user_id,revision,updated_at) VALUES (?,7,?),(-42,3,?)
                """, member, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbcTemplate.update("""
                INSERT INTO privacy_notification_notices(user_id,target_type,target_id,notice_version,
                    requested_at,expires_at,purge_after) VALUES (-42,'REGION','11','test',?,?,?)
                """, Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(100)),
                Timestamp.from(NOW.plusSeconds(1000)));

        assertEquals(2, retention.purgeChunk(NOW, 500));
        assertEquals(7, jdbcTemplate.queryForObject(
                "SELECT revision FROM privacy_notification_states WHERE user_id = ?", Long.class, member));
        assertEquals(before, legacySnapshot());
    }

    private long seedLegacyRecords() {
        String email = UUID.randomUUID() + "@example.com";
        long member = jdbcTemplate.queryForObject("""
                INSERT INTO users(login_identifier,email,created_at) VALUES (?,?,?) RETURNING id
                """, Long.class, UUID.randomUUID().toString(), email, Timestamp.from(NOW));
        jdbcTemplate.update("INSERT INTO notification_email_preferences(user_id,email,updated_at) VALUES (?,?,?)",
                member, email, Timestamp.from(NOW));
        jdbcTemplate.update("""
                INSERT INTO notification_subscriptions(user_id,target_type,target_id,active,updated_at,expires_at)
                VALUES (?,'REGION','11',true,?,?)
                """, member, Timestamp.from(NOW.minusSeconds(100)), Timestamp.from(NOW.minusSeconds(10)));
        UUID guest = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO notification_guest_email_preferences(client_id,email,updated_at) VALUES (?,?,?)
                """, guest, email, Timestamp.from(NOW));
        jdbcTemplate.update("""
                INSERT INTO notification_guest_subscriptions(client_id,target_type,target_id,active,updated_at,expires_at)
                VALUES (?,'REGION','11',false,?,?)
                """, guest, Timestamp.from(NOW.minusSeconds(100)), Timestamp.from(NOW.minusSeconds(10)));
        jdbcTemplate.update("""
                INSERT INTO notification_guest_cancellation_requests(id,email,requested_at,failed_attempts)
                VALUES (?,?,?,0)
                """, UUID.randomUUID(), email, Timestamp.from(NOW.minusSeconds(10000000)));
        jdbcTemplate.update("""
                INSERT INTO notification_interest_events(event_id,session_id,event_type,source,target_type,target_id,
                    created_at) VALUES (?,?,'CLICKED','REGION_SEARCH','REGION','11',?)
                """, UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(NOW.minusSeconds(10000000)));
        return member;
    }

    private Map<String, List<String>> legacySnapshot() {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (String table : LEGACY_TABLES) {
            snapshot.put(table, jdbcTemplate.queryForList(
                    "SELECT to_jsonb(records)::text FROM " + table + " records ORDER BY to_jsonb(records)::text",
                    String.class));
        }
        return snapshot;
    }
}
