package com.toadzip.backend.privacy.repository;

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
public class PrivacyNotificationRepository {

    private final JdbcTemplate jdbcTemplate;

    public long lockUser(long userId, Instant now) {
        jdbcTemplate.query("SELECT id FROM users WHERE id = ? FOR UPDATE",
                (row, number) -> row.getLong(1), userId).stream().findFirst()
                .orElseThrow(NotificationMemberMissingException::new);
        jdbcTemplate.update("""
                INSERT INTO privacy_notification_states(user_id, revision, updated_at) VALUES (?, 0, ?)
                ON CONFLICT (user_id) DO NOTHING
                """, userId, Timestamp.from(now));
        return jdbcTemplate.queryForObject("""
                SELECT revision FROM privacy_notification_states WHERE user_id = ? FOR UPDATE
                """, Long.class, userId);
    }

    public Optional<NotificationSettingReceipt> findReceipt(UUID eventId) {
        return jdbcTemplate.query("""
                SELECT event_id, user_id, request_fingerprint, target_type, target_id, outcome,
                    created_at, settings_revision
                FROM privacy_notification_receipts WHERE event_id = ?
                """, (row, number) -> new NotificationSettingReceipt(row.getObject("event_id", UUID.class),
                row.getLong("user_id"), row.getString("request_fingerprint"),
                NotificationTargetType.valueOf(row.getString("target_type")), row.getString("target_id"),
                NotificationInterestOutcome.valueOf(row.getString("outcome")),
                row.getTimestamp("created_at").toInstant(), row.getLong("settings_revision")), eventId)
                .stream().findFirst();
    }

    public CurrentTarget currentTarget(long userId, NotificationTargetType type, String targetId, Instant now) {
        return jdbcTemplate.query("""
                SELECT subscriptions.active AND subscriptions.expires_at > ?
                    AS currently_active, subscriptions.expires_at AS expires_at,
                    notices.notice_version, notices.requested_at
                FROM notification_subscriptions subscriptions
                LEFT JOIN privacy_notification_notices notices USING (user_id, target_type, target_id)
                WHERE subscriptions.user_id = ? AND subscriptions.target_type = ? AND subscriptions.target_id = ?
                """, (row, number) -> new CurrentTarget(row.getBoolean("currently_active"),
                instant(row, "expires_at"), row.getString("notice_version"), instant(row, "requested_at")),
                Timestamp.from(now), userId, type.name(), targetId).stream().findFirst()
                .orElse(new CurrentTarget(false, null, null, null));
    }

    public void recordNotice(NotificationSettingCommand command, Instant now, Instant expiresAt, Instant purgeAfter) {
        jdbcTemplate.update("""
                INSERT INTO privacy_notification_notices
                    (user_id, target_type, target_id, notice_version, requested_at, expires_at, purge_after)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, target_type, target_id) DO UPDATE
                SET notice_version = EXCLUDED.notice_version, requested_at = EXCLUDED.requested_at,
                    expires_at = EXCLUDED.expires_at, purge_after = EXCLUDED.purge_after
                """, command.userId(), command.targetType().name(), command.targetId(), command.noticeVersion(),
                Timestamp.from(now), Timestamp.from(expiresAt), Timestamp.from(purgeAfter));
    }

    public void retireNotice(NotificationSettingCommand command, Instant purgeAfter) {
        jdbcTemplate.update("""
                UPDATE privacy_notification_notices SET purge_after = ?
                WHERE user_id = ? AND target_type = ? AND target_id = ?
                """, Timestamp.from(purgeAfter), command.userId(), command.targetType().name(), command.targetId());
    }

    public boolean insertReceipt(NotificationSettingCommand command, NotificationInterestOutcome outcome,
            String noticeVersion, Instant now, long revision, Instant purgeAfter) {
        return jdbcTemplate.update("""
                INSERT INTO privacy_notification_receipts
                    (event_id, event_type, source, target_type, target_id, created_at,
                     request_fingerprint, outcome, user_id, notice_version, settings_revision, purge_after)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, command.eventId(), command.eventType().name(), command.source().name(),
                command.targetType().name(), command.targetId(), Timestamp.from(now), command.fingerprint(),
                outcome.name(), command.userId(), noticeVersion, revision, Timestamp.from(purgeAfter)) == 1;
    }

    public void updateRevision(long userId, long previousRevision, long revision, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE privacy_notification_states SET revision = ?, updated_at = ? WHERE user_id = ? AND revision = ?
                """, revision, Timestamp.from(now), userId, previousRevision);
        if (updated != 1) {
            throw new ConcurrencyFailureException("알림 설정을 저장할 수 없습니다.");
        }
    }

    public MemberNotificationSettingsResponse currentSettings(long userId, long revision, Instant now) {
        List<MemberNotificationSettingsResponse.Target> targets = jdbcTemplate.query("""
                SELECT subscriptions.target_type, subscriptions.target_id,
                    COALESCE(complex.name, announcement.name) AS target_name,
                    notices.notice_version, notices.requested_at,
                    subscriptions.expires_at AS expires_at
                FROM notification_subscriptions subscriptions
                LEFT JOIN privacy_notification_notices notices USING (user_id, target_type, target_id)
                LEFT JOIN housing_complexes complex
                    ON subscriptions.target_type = 'COMPLEX' AND subscriptions.target_id = complex.id::text
                LEFT JOIN announcements announcement
                    ON subscriptions.target_type = 'ANNOUNCEMENT' AND subscriptions.target_id = announcement.id::text
                WHERE subscriptions.user_id = ? AND subscriptions.active = true
                    AND subscriptions.expires_at > ?
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
