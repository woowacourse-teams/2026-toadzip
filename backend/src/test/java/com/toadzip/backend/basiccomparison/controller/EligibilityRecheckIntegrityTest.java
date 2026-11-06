package com.toadzip.backend.basiccomparison.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.basiccomparison.EligibilityFixtures;
import com.toadzip.backend.basiccomparison.repository.EligibilityWorkRepository;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceUtils;

class EligibilityRecheckIntegrityTest extends ComparisonIntegrationSupport {
    @Autowired EligibilityWorkRepository jobs;

    @Test
    void 같은연도_기준표는_새_ID로_정정하고_참조중인_기준표는_수정하거나_행을_옮길_수_없다() throws Exception {
        long notice = announcement();
        publish(notice, saveDraft(notice, null, 0, EligibilityFixtures.d05())).andExpect(status().isOk());
        long revised = jdbc.queryForObject("INSERT INTO income_standard_tables"
                + " (standard_kind, base_year, source_note, max_listed_household_size)"
                + " VALUES ('URBAN_WORKER_MONTHLY_INCOME', 2025, 'revision integration fixture', 9) RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO income_standard_values(table_id, household_size, amount_100_krw) VALUES (?, 9, 100)",
                revised);
        rejected("UPDATE income_standard_values SET amount_100_krw = 1 WHERE table_id = 1 AND household_size = 2");
        rejected("UPDATE income_standard_values SET table_id = 1 WHERE table_id = " + revised);
        assertThat(jdbc.queryForObject("SELECT amount_100_krw FROM income_standard_values"
                + " WHERE table_id = 1 AND household_size = 2", Long.class)).isEqualTo(5866270);
    }

    @Test
    void 조회수는_판정기준을_변경하지_않지만_접수일정_정정은_변경한다() {
        long notice = announcement();
        long generation = jobs.notice(notice, false).orElseThrow().generation();
        jdbc.update("UPDATE announcements SET view_count = view_count + 1 WHERE id = ?", notice);
        assertThat(jobs.notice(notice, false).orElseThrow().generation()).isEqualTo(generation);
        jdbc.update("UPDATE announcements SET application_end_date = application_end_date + 1 WHERE id = ?", notice);
        assertThat(jobs.notice(notice, false).orElseThrow().generation()).isGreaterThan(generation);
    }

    @Test
    void 공고_대표날짜가_같아도_검증일정_변경은_재판정_요청을_남긴다() {
        long notice = announcement();
        jdbc.update("UPDATE announcements SET application_schedule_reviewed = true WHERE id = ?", notice);
        long generation = jobs.notice(notice, false).orElseThrow().generation();
        long schedule = jdbc.queryForObject("""
                INSERT INTO announcement_application_schedules
                    (announcement_id, state, start_date, end_date, source_url, source_page)
                VALUES (?, 'CONFIRMED', '2026-10-26', '2026-10-30', 'https://example.test/notice', 1)
                RETURNING id
                """, Long.class, notice);
        assertThat(jobs.notice(notice, false).orElseThrow().generation()).isEqualTo(generation + 1);
        jdbc.update("UPDATE announcement_application_schedules SET end_date = '2026-11-02' WHERE id = ?", schedule);
        assertThat(jobs.notice(notice, false).orElseThrow().generation()).isEqualTo(generation + 2);
        jdbc.update("DELETE FROM announcement_application_schedules WHERE id = ?", schedule);
        assertThat(jobs.notice(notice, false).orElseThrow().generation()).isEqualTo(generation + 3);
        assertThat(jdbc.queryForObject("SELECT state FROM comparison_notice_rechecks WHERE announcement_id = ?",
                String.class, notice)).isEqualTo("PENDING");
    }

    private void rejected(String sql) throws Exception {
        var connection = DataSourceUtils.getConnection(jdbc.getDataSource());
        var savepoint = connection.setSavepoint();
        try (var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(sql))
                    .isInstanceOfSatisfying(SQLException.class, exception ->
                            assertThat(exception.getSQLState()).isEqualTo("23514"));
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            DataSourceUtils.releaseConnection(connection, jdbc.getDataSource());
        }
    }
}
