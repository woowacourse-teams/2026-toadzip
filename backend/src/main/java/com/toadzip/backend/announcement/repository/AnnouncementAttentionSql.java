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

    /** 보일 공급이 없다: 확정한 공고는 제외하지 않은 정본 공급행, 확정 전 공고는 기관 준비 줄이 없다. */
    public static String supplyMissing(String alias) {
        return """
                (CASE WHEN %1$s.supply_confirmed_at IS NOT NULL THEN NOT EXISTS (
                    SELECT 1 FROM supply_rows r WHERE r.announcement_id = %1$s.id AND r.admin_excluded = false)
                  ELSE NOT EXISTS (SELECT 1 FROM agency_supply_lines l WHERE l.announcement_id = %1$s.id) END)
                """.formatted(alias);
    }
}
