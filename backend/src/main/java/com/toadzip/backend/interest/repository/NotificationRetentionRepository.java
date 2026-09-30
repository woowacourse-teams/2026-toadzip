package com.toadzip.backend.interest.repository;

import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationRetentionRepository {

    private final JdbcTemplate jdbcTemplate;

    private final GuestCancellationRepository cancellationRepository;

    public void purge(Instant now) {
        cancellationRepository.purgeOldRequests(now.minusSeconds(30L * 24 * 60 * 60));
        jdbcTemplate.update("DELETE FROM notification_interest_events WHERE created_at < ?",
                Timestamp.from(now.minusSeconds(90L * 24 * 60 * 60)));
        jdbcTemplate.update("""
                DELETE FROM notification_guest_email_preferences preferences
                WHERE NOT EXISTS (
                    SELECT 1 FROM notification_guest_subscriptions subscriptions
                    WHERE subscriptions.client_id = preferences.client_id
                      AND subscriptions.active = true AND subscriptions.expires_at > ?)
                """, Timestamp.from(now));
        jdbcTemplate.update("""
                DELETE FROM notification_guest_cancellation_requests requests
                WHERE NOT EXISTS (
                    SELECT 1 FROM notification_guest_email_preferences preferences
                    JOIN notification_guest_subscriptions subscriptions
                      ON subscriptions.client_id = preferences.client_id
                    WHERE lower(preferences.email) = requests.email
                      AND subscriptions.active = true AND subscriptions.expires_at > ?)
                """, Timestamp.from(now));
        jdbcTemplate.update("""
                DELETE FROM notification_email_preferences preferences
                WHERE NOT EXISTS (
                    SELECT 1 FROM notification_subscriptions subscriptions
                    WHERE subscriptions.user_id = preferences.user_id
                      AND subscriptions.active = true AND subscriptions.expires_at > ?)
                """, Timestamp.from(now));
        jdbcTemplate.update("""
                DELETE FROM notification_subscriptions
                WHERE expires_at <= ? OR (active = false AND updated_at < ?)
                """, Timestamp.from(now), Timestamp.from(now.minusSeconds(90L * 24 * 60 * 60)));
    }
}
