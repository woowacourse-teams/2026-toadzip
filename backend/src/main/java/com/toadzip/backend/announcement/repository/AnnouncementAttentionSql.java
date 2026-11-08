package com.toadzip.backend.announcement.repository;

public final class AnnouncementAttentionSql {
    private AnnouncementAttentionSql() { }

    public static String scheduleMissing(String alias) {
        return """
                (CASE WHEN %1$s.application_schedule_reviewed THEN NOT EXISTS (
                    SELECT 1 FROM announcement_application_schedules s WHERE s.announcement_id = %1$s.id)
                  ELSE %1$s.application_start_date IS NULL OR %1$s.application_end_date IS NULL END)
                """.formatted(alias);
    }

    public static String supplyMissing(String alias) {
        return "NOT EXISTS (SELECT 1 FROM supply_rows r WHERE r.announcement_id = " + alias
                + ".id AND r.admin_excluded = false)";
    }
}
