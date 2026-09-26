package com.toadzip.backend.announcement.repository;

public final class ApplicationScheduleSql {

    private ApplicationScheduleSql() {
    }

    public static String status(String announcementAlias, String complexIdExpression) {
        String applicable = applicable(announcementAlias, complexIdExpression);
        String current = applicable + " AND schedule.start_date <= :today AND schedule.end_date >= :today";
        return """
                (CASE
                  WHEN %1$s.id IS NULL THEN NULL
                  WHEN %1$s.status IN ('CANCELLATION', '취소공고') THEN 'CANCELLED'
                  WHEN %1$s.application_schedule_reviewed THEN
                    CASE
                      WHEN EXISTS (SELECT 1 FROM announcement_application_schedules schedule
                        WHERE %2$s AND schedule.state = 'CONFIRMED') THEN 'APPLYING'
                      WHEN EXISTS (SELECT 1 FROM announcement_application_schedules schedule
                        WHERE %2$s) THEN 'CONDITIONAL'
                      WHEN EXISTS (SELECT 1 FROM announcement_application_schedules schedule
                        WHERE %3$s AND schedule.start_date > :today) THEN 'BEFORE_APPLICATION'
                      ELSE 'CLOSED'
                    END
                  WHEN %1$s.application_start_date > :today THEN 'BEFORE_APPLICATION'
                  WHEN %1$s.application_end_date < :today THEN 'CLOSED'
                  ELSE 'APPLYING'
                END)
                """.formatted(announcementAlias, current, applicable);
    }

    public static String confirmedDeadline(String announcementAlias, String complexIdExpression) {
        return """
                (CASE WHEN %1$s.application_schedule_reviewed THEN
                    (SELECT MIN(schedule.end_date) FROM announcement_application_schedules schedule
                     WHERE %2$s AND schedule.state = 'CONFIRMED' AND schedule.end_date >= :today)
                 ELSE %1$s.application_end_date END)
                """.formatted(announcementAlias, applicable(announcementAlias, complexIdExpression));
    }

    private static String applicable(String announcementAlias, String complexIdExpression) {
        String applicable = "schedule.announcement_id = " + announcementAlias + ".id";
        if (complexIdExpression != null) {
            applicable += " AND (schedule.housing_complex_id IS NULL OR schedule.housing_complex_id = "
                    + complexIdExpression + ")";
        }
        return applicable;
    }

}
