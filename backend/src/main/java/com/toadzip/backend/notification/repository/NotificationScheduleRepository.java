package com.toadzip.backend.notification.repository;

import com.toadzip.backend.notification.domain.ScheduleReminder;
import java.sql.ResultSet;
import java.sql.Timestamp;
import com.toadzip.backend.announcement.domain.ScheduleType;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationScheduleRepository {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;

    public List<ScheduleReminder> due(Instant now) {
        var today = now.atZone(SEOUL).toLocalDate();
        if (now.isBefore(today.atTime(9, 0).atZone(SEOUL).toInstant())) {
            return List.of();
        }
        return reminders(today, null);
    }

    public boolean isCurrent(ScheduleReminder reminder, Instant now) {
        LocalDate day = reminder.dueAt().atZone(SEOUL).toLocalDate();
        if (!now.isBefore(day.plusDays(1).atStartOfDay(SEOUL).toInstant())) {
            return false;
        }
        return reminders(day, reminder.announcementId()).stream().anyMatch(current -> current.key().equals(reminder.key()));
    }

    public boolean hasSubscribers(ScheduleReminder reminder, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM notification_subscriptions
                    WHERE target_type='ANNOUNCEMENT' AND target_id=? AND active
                        AND activated_at<=? AND expires_at>?)
                """, Boolean.class, Long.toString(reminder.announcementId()),
                Timestamp.from(reminder.dueAt()), Timestamp.from(now)));
    }

    private List<ScheduleReminder> reminders(LocalDate day, Long announcementId) {
        LocalDate tomorrow = day.plusDays(1);
        String owner = "";
        if (announcementId != null) {
            owner = " AND a.id=" + announcementId;
        }
        // Non-application source dates are official agency/admin rows; midnight can be an unknown-time sentinel.
        List<ScheduleRow> rows = new ArrayList<>(jdbc.query("""
                SELECT a.id,a.original_url,s.schedule_type,s.name,s.start_at::date AS start_date,
                    s.end_at::date AS end_date,NULLIF(s.start_at::time,TIME '00:00') AS start_time,
                    NULLIF(s.end_at::time,TIME '00:00') AS end_time,
                    s.complex_name AS scope,NULL AS application_condition,NULL::integer AS source_page
                FROM announcement_schedules s JOIN announcements a ON a.id=s.announcement_id
                WHERE NOT a.admin_deleted AND a.status IN ('ORIGINAL','원공고','CORRECTION','정정공고')
                    AND NOT EXISTS(SELECT 1 FROM announcements successor WHERE successor.previous_announcement_id=a.id AND NOT successor.admin_deleted)
                    AND s.schedule_type IN ('DOCUMENT_SUBMISSION','서류제출','WINNER_ANNOUNCEMENT','당첨자발표','CONTRACT','계약')
                    AND ((s.start_at>=? AND s.start_at<?) OR (s.end_at>=? AND s.end_at<?))
                """ + owner, this::row, tomorrow.atStartOfDay(), tomorrow.plusDays(1).atStartOfDay(),
                tomorrow.atStartOfDay(), tomorrow.plusDays(1).atStartOfDay()));
        rows.addAll(jdbc.query("""
                SELECT a.id,s.source_url AS original_url,'APPLICATION' AS schedule_type,'접수' AS name,
                    s.start_date,s.end_date,s.start_time,s.end_time,
                    concat_ws(' · ',c.name,s.supply_rank) AS scope,s.application_condition,s.source_page
                FROM announcement_application_schedules s JOIN announcements a ON a.id=s.announcement_id
                    LEFT JOIN housing_complexes c ON c.id=s.housing_complex_id
                WHERE NOT a.admin_deleted AND a.application_schedule_reviewed AND s.state='CONFIRMED'
                    AND (c.id IS NULL OR NOT c.admin_deleted)
                    AND a.status IN ('ORIGINAL','원공고','CORRECTION','정정공고')
                    AND NOT EXISTS(SELECT 1 FROM announcements successor WHERE successor.previous_announcement_id=a.id AND NOT successor.admin_deleted)
                    AND (s.start_date=? OR s.end_date=?)
                """ + owner, this::row, tomorrow, tomorrow));
        List<ScheduleReminder> result = new ArrayList<>();
        Instant due = day.atTime(9, 0).atZone(SEOUL).toInstant();
        for (var row : rows) {
            if (row.start().equals(tomorrow)) {
                String phase = "START";
                if (row.start().equals(row.end())) {
                    phase = "START_END";
                }
                result.add(reminder(row, phase, due));
            }
            if (!row.kind().equals("WINNER_ANNOUNCEMENT") && !row.start().equals(row.end()) && row.end().equals(tomorrow)) {
                result.add(reminder(row, "END", due));
            }
        }
        return result.stream().distinct().toList();
    }

    private ScheduleRow row(ResultSet row, int index) throws SQLException {
        String kind = ScheduleType.fromStoredValue(row.getString("schedule_type")).name();
        return new ScheduleRow(row.getLong("id"), kind, row.getString("name"), row.getObject("start_date", LocalDate.class),
                row.getObject("end_date", LocalDate.class), row.getObject("start_time", LocalTime.class),
                row.getObject("end_time", LocalTime.class), row.getString("scope"), row.getString("application_condition"),
                row.getString("original_url"), row.getObject("source_page", Integer.class));
    }
    private ScheduleReminder reminder(ScheduleRow row, String phase, Instant due) {
        return new ScheduleReminder(row.announcementId(), row.kind(), row.name(), row.start(), row.end(),
                row.startTime(), row.endTime(), row.scope(), row.condition(), row.url(), row.page(), phase, due);
    }
    private record ScheduleRow(long announcementId, String kind, String name, LocalDate start, LocalDate end,
            LocalTime startTime, LocalTime endTime, String scope, String condition, String url, Integer page) { }
}
