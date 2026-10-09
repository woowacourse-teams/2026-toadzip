package com.toadzip.backend.interest.repository;

import com.toadzip.backend.interest.domain.NotificationInterestOutcome;
import com.toadzip.backend.interest.domain.NotificationSettingCommand;
import com.toadzip.backend.interest.domain.NotificationSettingReceipt;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.MemberNotificationSettingsResponse;
import com.toadzip.backend.interest.dto.NotificationSettingsResponse.CurrentTarget;
import com.toadzip.backend.interest.exception.NotificationMemberMissingException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationSettingsRepository {

    private final JdbcTemplate jdbcTemplate;

    public long lockUser(long userId) {
        return jdbcTemplate.query("SELECT notification_settings_revision FROM users WHERE id = ? FOR UPDATE",
                (row, number) -> row.getLong(1), userId).stream().findFirst()
                .orElseThrow(NotificationMemberMissingException::new);
    }

    public Optional<NotificationSettingReceipt> findReceipt(UUID eventId) {
        return jdbcTemplate.query("""
                SELECT event_id, user_id, request_fingerprint, target_type, target_id, outcome,
                    created_at, settings_revision
                FROM notification_interest_events WHERE event_id = ?
                """, (row, number) -> new NotificationSettingReceipt(row.getObject("event_id", UUID.class),
                row.getLong("user_id"), row.getString("request_fingerprint"),
                NotificationTargetType.valueOf(row.getString("target_type")), row.getString("target_id"),
                NotificationInterestOutcome.valueOf(row.getString("outcome")),
                row.getTimestamp("created_at").toInstant(), row.getLong("settings_revision")), eventId)
                .stream().findFirst();
    }

    public CurrentTarget currentTarget(long userId, NotificationTargetType type, String targetId, Instant now) {
        return jdbcTemplate.query("""
                SELECT active AND expires_at > ? AS currently_active, expires_at, notice_version, requested_at
                FROM notification_subscriptions WHERE user_id = ? AND target_type = ? AND target_id = ?
                """, (row, number) -> new CurrentTarget(row.getBoolean("currently_active"),
                instant(row, "expires_at"), row.getString("notice_version"), instant(row, "requested_at")),
                Timestamp.from(now), userId, type.name(), targetId).stream().findFirst()
                .orElse(new CurrentTarget(false, null, null, null));
    }

    public void activate(NotificationSettingCommand command, Instant now, Instant expiresAt, Instant purgeAfter) {
        jdbcTemplate.update("""
                INSERT INTO notification_subscriptions
                    (user_id, target_type, target_id, active, updated_at, expires_at,
                     notice_version, requested_at, purge_after)
                VALUES (?, ?, ?, true, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT uk_notification_subscription_target DO UPDATE
                SET active = true, updated_at = EXCLUDED.updated_at, expires_at = EXCLUDED.expires_at,
                    notice_version = EXCLUDED.notice_version, requested_at = EXCLUDED.requested_at,
                    purge_after = EXCLUDED.purge_after
                """, command.userId(), command.targetType().name(), command.targetId(), Timestamp.from(now),
                Timestamp.from(expiresAt), command.noticeVersion(), Timestamp.from(now), Timestamp.from(purgeAfter));
    }

    public void cancel(NotificationSettingCommand command, Instant now, Instant purgeAfter) {
        jdbcTemplate.update("""
                UPDATE notification_subscriptions SET active = false, updated_at = ?, purge_after = ?
                WHERE user_id = ? AND target_type = ? AND target_id = ?
                """, Timestamp.from(now), Timestamp.from(purgeAfter), command.userId(),
                command.targetType().name(), command.targetId());
    }

    public boolean insertReceipt(NotificationSettingCommand command, NotificationInterestOutcome outcome,
            String noticeVersion, Instant now, long revision) {
        return jdbcTemplate.update("""
                INSERT INTO notification_interest_events
                    (event_id, session_id, event_type, source, target_type, target_id, created_at,
                     request_fingerprint, outcome, user_id, notice_version, settings_revision)
                VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, command.eventId(), command.eventType().name(), command.source().name(),
                command.targetType().name(), command.targetId(), Timestamp.from(now), command.fingerprint(),
                outcome.name(), command.userId(), noticeVersion, revision) == 1;
    }

    public void updateRevision(long userId, long previousRevision, long revision) {
        int updated = jdbcTemplate.update("""
                UPDATE users SET notification_settings_revision = ? WHERE id = ? AND notification_settings_revision = ?
                """, revision, userId, previousRevision);
        if (updated != 1) {
            throw new ConcurrencyFailureException("알림 설정을 저장할 수 없습니다.");
        }
    }

    public MemberNotificationSettingsResponse currentSettings(long userId, long revision, Instant now) {
        List<MemberNotificationSettingsResponse.Target> targets = jdbcTemplate.query("""
                SELECT subscriptions.target_type, subscriptions.target_id,
                    COALESCE(complex.name, announcement.name) AS target_name,
                    subscriptions.notice_version, subscriptions.requested_at, subscriptions.expires_at
                FROM notification_subscriptions subscriptions
                LEFT JOIN housing_complexes complex
                    ON subscriptions.target_type = 'COMPLEX' AND subscriptions.target_id = complex.id::text
                LEFT JOIN announcements announcement
                    ON subscriptions.target_type = 'ANNOUNCEMENT' AND subscriptions.target_id = announcement.id::text
                WHERE subscriptions.user_id = ? AND subscriptions.active = true AND subscriptions.expires_at > ?
                ORDER BY subscriptions.target_type, subscriptions.target_id
                """, (row, number) -> new MemberNotificationSettingsResponse.Target(
                NotificationTargetType.valueOf(row.getString("target_type")), row.getString("target_id"),
                row.getString("target_name"), row.getString("notice_version"), instant(row, "requested_at"),
                instant(row, "expires_at")), userId, Timestamp.from(now));
        return new MemberNotificationSettingsResponse(Long.toString(userId), revision, targets);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        if (value == null) {
            return null;
        }
        return value.toInstant();
    }
}
