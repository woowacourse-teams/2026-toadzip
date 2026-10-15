package com.toadzip.backend.notification.repository;

import com.toadzip.backend.notification.domain.NotificationDeviceState;
import com.toadzip.backend.notification.domain.NotificationDeviceSummary;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationPushDeviceRepository {
    private final JdbcTemplate jdbc;

    public void lockRegistration(UUID installation, String fid) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", (row, number) -> 0,
                "notification-device:" + installation);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", (row, number) -> 0,
                "notification-fid:" + fid);
    }

    public Optional<NotificationDeviceState> find(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT * FROM notification_push_devices WHERE id=?", (row, number) ->
                new NotificationDeviceState(row.getObject("id", UUID.class), row.getLong("user_id"),
                        row.getString("fid"), row.getLong("binding_version"), row.getBoolean("enabled"),
                        row.getTimestamp("consented_at").toInstant(), row.getBoolean("resume_after_login"),
                        row.getString("platform"), row.getString("browser")), id).stream().findFirst();
    }

    public List<NotificationDeviceSummary> findReceivingDevices(long userId) {
        return jdbc.query("""
                SELECT id,binding_version,platform,browser,last_seen_at,enabled,resume_after_login
                FROM notification_push_devices WHERE user_id=? AND (enabled OR resume_after_login)
                ORDER BY last_seen_at DESC,id
                """, (row, number) -> new NotificationDeviceSummary(row.getObject("id", UUID.class),
                row.getLong("binding_version"), row.getString("platform"), row.getString("browser"),
                row.getTimestamp("last_seen_at").toInstant(), row.getBoolean("enabled"),
                row.getBoolean("resume_after_login")), userId);
    }

    public boolean hasOtherInstallation(UUID id, String fid) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM notification_push_devices WHERE fid=? AND id<>?)", Boolean.class, fid, id));
    }

    public void save(NotificationDeviceState device, Instant now) {
        jdbc.update("""
                INSERT INTO notification_push_devices(id,user_id,fid,binding_version,enabled,
                    consented_at,registered_at,last_seen_at,platform,browser) VALUES(?,?,?,?,true,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET user_id=EXCLUDED.user_id,fid=EXCLUDED.fid,
                    binding_version=EXCLUDED.binding_version,enabled=true,resume_after_login=false,
                    consented_at=EXCLUDED.consented_at,last_seen_at=EXCLUDED.last_seen_at,
                    platform=EXCLUDED.platform,browser=EXCLUDED.browser
                """, device.id(), device.userId(), device.fid(), device.bindingVersion(),
                Timestamp.from(device.consentedAt()), Timestamp.from(now), Timestamp.from(now),
                device.platform(), device.browser());
    }

    public boolean disable(long userId, UUID id, long version) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", (row, number) -> 0,
                "notification-device:" + id);
        return jdbc.update("""
                UPDATE notification_push_devices SET enabled=false,resume_after_login=false,
                    binding_version=binding_version+1
                WHERE id=? AND user_id=? AND binding_version=? AND (enabled OR resume_after_login)
                """, id, userId, version) == 1;
    }
    public void pauseForLogout(long userId, UUID id, long version, Instant now) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", (row, number) -> 0,
                "notification-device:" + id);
        jdbc.update("""
                UPDATE notification_push_devices SET enabled=false,resume_after_login=true,
                    binding_version=binding_version+1,last_seen_at=?
                WHERE id=? AND user_id=? AND binding_version=? AND enabled
                """, Timestamp.from(now), id, userId, version);
    }
}
