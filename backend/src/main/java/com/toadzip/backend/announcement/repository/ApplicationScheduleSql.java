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
                  WHEN %1$s.application_start_date IS NULL OR %1$s.application_end_date IS NULL THEN NULL
                  WHEN %1$s.application_start_date > :today THEN 'BEFORE_APPLICATION'
                  WHEN %1$s.application_end_date < :today THEN 'CLOSED'
                  ELSE 'APPLYING'
                END)
                """.formatted(announcementAlias, current, applicable);
    }

    public static String displayPeriodColumns(String announcementAlias) {
        return """
                CASE WHEN %1$s.application_schedule_reviewed THEN application_period.start_date
                     ELSE %1$s.application_start_date END AS application_start_date,
                CASE WHEN %1$s.application_schedule_reviewed THEN application_period.end_date
                     ELSE %1$s.application_end_date END AS application_end_date,
                CASE WHEN %1$s.application_schedule_reviewed THEN
                    CASE WHEN application_period.state = 'CONFIRMED' AND application_period.end_date >= :today
                         THEN application_period.end_date END
                     ELSE %1$s.application_end_date END AS confirmed_application_end_date
                """.formatted(announcementAlias);
    }

    public static String displayPeriodJoin(String announcementAlias, String complexIdExpression) {
        // 현재 확정 접수, 현재 조건부 접수, 다음 접수, 마지막으로 끝난 접수 순으로 하나의 기간을 선택한다.
        // 표시 시작일·종료일과 D-day가 모두 이 일정에 속하도록 같은 행을 사용한다.
        return """
                LEFT JOIN LATERAL (
                    SELECT schedule.start_date, schedule.end_date, schedule.state
                    FROM announcement_application_schedules schedule
                    WHERE %1$s.application_schedule_reviewed AND %2$s
                    ORDER BY CASE
                        WHEN schedule.start_date <= :today AND schedule.end_date >= :today THEN
                            CASE WHEN schedule.state = 'CONFIRMED' THEN 0 ELSE 1 END
                        WHEN schedule.start_date > :today THEN 2
                        ELSE 3
                    END,
                    CASE WHEN schedule.start_date > :today THEN schedule.start_date END ASC NULLS LAST,
                    CASE WHEN schedule.start_date > :today AND schedule.state = 'CONFIRMED' THEN 0 ELSE 1 END,
                    CASE WHEN schedule.end_date >= :today THEN schedule.end_date END ASC NULLS LAST,
                    schedule.end_date DESC, schedule.start_date ASC, schedule.id ASC
                    LIMIT 1
                ) application_period ON TRUE
                """.formatted(announcementAlias, applicable(announcementAlias, complexIdExpression));
    }

    /**
     * 공고의 진행 단계를 0(공고중·접수중), 1(접수마감 목록에 남은 마감·취소), 2(과거 공고)로 나타낸다.
     * 마감 공고는 마지막 일정 후 정해진 기간이 지나면 과거 공고다. {@code :today}와 {@code :followUpCutoff}가 필요하다.
     */
    public static String phase(String announcementAlias) {
        return """
                (CASE %2$s
                  WHEN 'CLOSED' THEN CASE WHEN %3$s THEN 1 ELSE 2 END
                  WHEN 'CANCELLED' THEN 1
                  ELSE 0
                END)
                """.formatted(announcementAlias, status(announcementAlias, null),
                followUpOpen(announcementAlias));
    }

    // 접수 종료일, 당첨자 발표일, 공고 일정 종료일 중 하나라도 기준일 이후면 마지막 일정이 아직 남아 있다.
    // 확인된 접수 일정의 마지막 종료일은 공고 접수 종료일에 반영되어 있다.
    private static String followUpOpen(String announcementAlias) {
        return """
                (%1$s.application_end_date >= :followUpCutoff
                  OR %1$s.winner_announcement_date >= :followUpCutoff
                  OR EXISTS (SELECT 1 FROM announcement_schedules follow_up_schedule
                    WHERE follow_up_schedule.announcement_id = %1$s.id
                      AND follow_up_schedule.end_at >= :followUpCutoff))
                """.formatted(announcementAlias);
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
