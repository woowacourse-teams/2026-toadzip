package com.toadzip.backend.basiccomparison.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.basiccomparison.ComparisonFixtures;
import com.toadzip.backend.basiccomparison.EligibilityFixtures;
import com.toadzip.backend.basiccomparison.domain.EligibilityWork.Kind;
import com.toadzip.backend.basiccomparison.repository.EligibilityResultRepository;
import com.toadzip.backend.basiccomparison.service.EligibilityRecheckTransactions;
import com.toadzip.backend.basiccomparison.service.EligibilityRecheckWorker;
import com.toadzip.backend.basiccomparison.service.EligibilitySnapshotService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EligibilityRecheckConcurrencyTest extends ComparisonIntegrationSupport {
    @Autowired EligibilityRecheckTransactions writes;
    @Autowired EligibilitySnapshotService snapshots;
    @Autowired EligibilityResultRepository results;
    @Autowired EligibilityRecheckWorker worker;

    @Test
    void 기준표_수정이_먼저_시작되면_최초_참조_저장은_수정완료까지_기다린다() throws Exception {
        long notice = transaction(this::announcement);
        long standard = unreferencedStandard();
        String content = standardReference(standard);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            transaction(() -> {
                jdbc.update("UPDATE income_standard_values SET amount_100_krw = 999"
                        + " WHERE table_id = ?", standard);
                var referencing = executor.submit(() -> transaction(() -> {
                    jdbc.execute("SET LOCAL lock_timeout = '250ms'");
                    insertReference(notice, content);
                    return true;
                }));
                assertThatThrownBy(() -> referencing.get(5, TimeUnit.SECONDS))
                        .hasRootCauseInstanceOf(java.sql.SQLException.class)
                        .hasStackTraceContaining("lock timeout");
                return true;
            });
            insertReference(notice, content);
            assertThat(jdbc.queryForObject("SELECT amount_100_krw FROM income_standard_values"
                    + " WHERE table_id = ? AND household_size = 2", Long.class, standard)).isEqualTo(999);
        } finally {
            jdbc.update("DELETE FROM announcements WHERE id = ?", notice);
            jdbc.update("DELETE FROM income_standard_values WHERE table_id = ?", standard);
            jdbc.update("DELETE FROM income_standard_tables WHERE id = ?", standard);
        }
    }

    @Test
    void 최초_참조_저장이_먼저_시작되면_기준표_수정은_기다린_뒤_거부된다() throws Exception {
        long notice = transaction(this::announcement);
        long standard = unreferencedStandard();
        String content = standardReference(standard);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            transaction(() -> {
                insertReference(notice, content);
                var mutation = executor.submit(() -> transaction(() -> {
                    jdbc.execute("SET LOCAL lock_timeout = '250ms'");
                    return jdbc.update("UPDATE income_standard_values SET amount_100_krw = 999"
                            + " WHERE table_id = ?", standard);
                }));
                assertThatThrownBy(() -> mutation.get(5, TimeUnit.SECONDS))
                        .hasRootCauseInstanceOf(java.sql.SQLException.class)
                        .hasStackTraceContaining("lock timeout");
                return true;
            });
            assertThatThrownBy(() -> jdbc.update("UPDATE income_standard_values SET amount_100_krw = 999"
                    + " WHERE table_id = ?", standard)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.update("DELETE FROM announcements WHERE id = ?", notice);
            jdbc.update("DELETE FROM income_standard_values WHERE table_id = ?", standard);
            jdbc.update("DELETE FROM income_standard_tables WHERE id = ?", standard);
        }
    }

    private long unreferencedStandard() {
        long standard = jdbc.queryForObject("INSERT INTO income_standard_tables"
                        + " (standard_kind, base_year, source_note, max_listed_household_size)"
                        + " VALUES ('URBAN_WORKER_MONTHLY_INCOME', 2025, 'concurrency fixture', 9) RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO income_standard_values(table_id, household_size, amount_100_krw)"
                + " SELECT ?, household_size, amount_100_krw FROM income_standard_values WHERE table_id = 1", standard);
        return standard;
    }

    private String standardReference(long standard) throws Exception {
        var content = json.valueToTree(EligibilityFixtures.d05());
        for (var parent : content.findParents("standardTableId")) {
            ((tools.jackson.databind.node.ObjectNode) parent).put("standardTableId", standard);
        }
        return json.writeValueAsString(content);
    }

    private void insertReference(long notice, String content) {
        jdbc.update("INSERT INTO basic_comparison_rule_sets(announcement_id, schema_version, content)"
                + " VALUES (?, 3, ?::jsonb)", notice, content);
    }

    @Test
    void 공고_writer와_최초결과_INSERT의_FK_잠금_순서가_교착하지_않는다() throws Exception {
        long notice = transaction(this::announcement);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            publish(notice, saveDraft(notice, null, 0, EligibilityFixtures.d05())).andExpect(status().isOk());
            jdbc.update("INSERT INTO basic_comparison_profiles(user_id, version, profile) VALUES (?, 1, ?::jsonb)",
                    USER, json.writeValueAsString(ComparisonFixtures.b()));
            var claimed = writes.claim(Kind.PROFILE).orElseThrow();
            var evaluated = snapshots.evaluate(USER, notice);
            CountDownLatch writerLocked = new CountDownLatch(1);
            var pendingWriter = transaction(() -> {
                jdbc.queryForObject("SELECT generation FROM comparison_notice_rechecks"
                        + " WHERE announcement_id = ? FOR UPDATE", Long.class, notice);
                var pending = executor.submit(() -> transaction(() -> {
                    jdbc.execute("SET LOCAL lock_timeout = '5s'");
                    jdbc.queryForObject("SELECT id FROM announcements WHERE id = ? FOR UPDATE", Long.class, notice);
                    writerLocked.countDown();
                    jdbc.update("UPDATE announcements SET status = 'CANCELLATION' WHERE id = ?", notice);
                    return true;
                }));
                await(writerLocked);
                assertThat(writes.commit(claimed, evaluated)).isTrue();
                return pending;
            });
            assertThat(pendingWriter.get(10, TimeUnit.SECONDS)).isTrue();
            var stored = results.find(USER, notice).orElseThrow();
            assertThat(snapshots.basis(USER, notice).noticeGeneration())
                    .isGreaterThan(stored.basis().noticeGeneration());
            assertThat(snapshots.basis(USER, notice).availability()).isEqualTo("CANCELLED");
        } finally {
            jdbc.update("DELETE FROM announcements WHERE id = ?", notice);
            jdbc.update("DELETE FROM basic_comparison_profiles WHERE user_id = ?", USER);
        }
    }

    @Test
    void 일시적_결과저장_실패는_입력_재수정_없이_복구된다() throws Exception {
        long notice = transaction(this::announcement);
        try {
            publish(notice, saveDraft(notice, null, 0, EligibilityFixtures.d05())).andExpect(status().isOk());
            jdbc.update("INSERT INTO basic_comparison_profiles(user_id, version, profile) VALUES (?, 1, ?::jsonb)",
                    USER, json.writeValueAsString(ComparisonFixtures.b()));
            jdbc.execute("ALTER TABLE comparison_latest_results ADD CONSTRAINT simulated_result_failure"
                    + " CHECK (announcement_id <> " + notice + ")");
            assertThat(worker.runOnce(Kind.PROFILE)).isTrue();
            assertThat(results.find(USER, notice)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT attempts FROM comparison_profile_rechecks WHERE user_id = ?",
                    Integer.class, USER)).isOne();
            assertThat(jdbc.queryForObject("SELECT cursor_announcement_id FROM comparison_profile_rechecks"
                            + " WHERE user_id = ?",
                    Long.class, USER)).isZero();
            jdbc.execute("ALTER TABLE comparison_latest_results DROP CONSTRAINT simulated_result_failure");
            jdbc.update("UPDATE comparison_profile_rechecks SET next_attempt_at = '1970-01-01 UTC'"
                    + " WHERE user_id = ?", USER);
            assertThat(worker.runOnce(Kind.PROFILE)).isTrue();
            assertThat(results.find(USER, notice).orElseThrow().basis().profileVersion()).isOne();
        } finally {
            jdbc.execute("ALTER TABLE comparison_latest_results DROP CONSTRAINT IF EXISTS simulated_result_failure");
            jdbc.update("DELETE FROM announcements WHERE id = ?", notice);
            jdbc.update("DELETE FROM basic_comparison_profiles WHERE user_id = ?", USER);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
