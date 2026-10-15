package com.toadzip.backend.interest.repository;

import com.toadzip.backend.interest.domain.NotificationInterestOutcome;
import com.toadzip.backend.interest.dto.AdminNotificationTrend;
import com.toadzip.backend.interest.dto.AdminNotificationTrend.Day;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationDailyActivityRepository {
    private static final ZoneId TIME_ZONE = ZoneId.of("Asia/Seoul");
    private final JdbcClient jdbc;

    public void record(NotificationInterestOutcome outcome, Instant now) {
        long activations = 0;
        long cancellations = 0;
        if (outcome == NotificationInterestOutcome.ACTIVATED) {
            activations = 1;
        }
        if (outcome == NotificationInterestOutcome.CANCELLED) {
            cancellations = 1;
        }
        if (activations + cancellations == 0) {
            return;
        }
        jdbc.sql("""
                INSERT INTO notification_daily_activity(day,activations,cancellations,first_recorded_at)
                VALUES (:day,:activations,:cancellations,:now)
                ON CONFLICT (day) DO UPDATE
                SET activations = notification_daily_activity.activations + EXCLUDED.activations,
                    cancellations = notification_daily_activity.cancellations + EXCLUDED.cancellations
                """).param("day", now.atZone(TIME_ZONE).toLocalDate()).param("activations", activations)
                .param("cancellations", cancellations).param("now", Timestamp.from(now)).update();
    }

    public AdminNotificationTrend trend(int days, Instant asOf) {
        LocalDate end = asOf.atZone(TIME_ZONE).toLocalDate();
        List<Row> rows = jdbc.sql("""
                WITH coverage AS (
                    SELECT MIN(first_recorded_at) AS started_at FROM notification_daily_activity
                    WHERE first_recorded_at <= :asOf
                ), dates AS (
                    SELECT generate_series(CAST(:start AS date),CAST(:end AS date),INTERVAL '1 day')::date AS day
                )
                SELECT dates.day, coverage.started_at,
                    CASE WHEN dates.day >= (coverage.started_at AT TIME ZONE 'Asia/Seoul')::date
                         THEN COALESCE(activity.activations,0) END AS activations,
                    CASE WHEN dates.day >= (coverage.started_at AT TIME ZONE 'Asia/Seoul')::date
                         THEN COALESCE(activity.cancellations,0) END AS cancellations
                FROM dates CROSS JOIN coverage LEFT JOIN notification_daily_activity activity USING (day)
                ORDER BY dates.day
                """).param("start", end.minusDays(days - 1L)).param("end", end).param("asOf", Timestamp.from(asOf))
                .query((result, index) -> {
                    Timestamp start = result.getTimestamp("started_at");
                    Instant startedAt = null;
                    if (start != null) {
                        startedAt = start.toInstant();
                    }
                    return new Row(new Day(result.getObject("day", LocalDate.class),
                            result.getObject("activations", Long.class), result.getObject("cancellations", Long.class)),
                            startedAt);
                }).list();
        return new AdminNotificationTrend(rows.stream().map(Row::day).toList(), rows.getFirst().startedAt(), asOf);
    }

    private record Row(Day day, Instant startedAt) {
    }
}
