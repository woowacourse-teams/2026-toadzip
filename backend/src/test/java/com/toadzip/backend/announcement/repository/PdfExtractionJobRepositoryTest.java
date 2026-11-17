package com.toadzip.backend.announcement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.announcement.domain.PdfExtractionJob;
import com.toadzip.backend.announcement.domain.PdfExtractionJobStatus;
import com.toadzip.backend.announcement.domain.PdfExtractionJobView;
import com.toadzip.backend.announcement.domain.PdfExtractionSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PdfExtractionJobRepositoryTest {
    private static final long ANNOUNCEMENT = 9_000_001L;
    private static final EnumSet<PdfExtractionJobStatus> ACTIVE = EnumSet.of(
            PdfExtractionJobStatus.PENDING, PdfExtractionJobStatus.RUNNING, PdfExtractionJobStatus.RETRY);

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PdfExtractionJobRepository jobs;

    @Test
    void 최근_결과는_더_늦게_실패한_작업이_아니라_가장_늦게_성공한_작업이다() {
        long older = finished(ANNOUNCEMENT, "SUCCEEDED", "2026-11-01T01:00:00Z");
        long newer = finished(ANNOUNCEMENT, "SUCCEEDED", "2026-11-01T02:00:00Z");
        finished(ANNOUNCEMENT, "FAILED", "2026-11-01T03:00:00Z");
        finished(ANNOUNCEMENT + 1, "SUCCEEDED", "2026-11-01T04:00:00Z");

        var latest = jobs.findLatestViews(PdfExtractionJob.KIND, ANNOUNCEMENT, PdfExtractionJobStatus.SUCCEEDED,
                Limit.of(1));

        assertThat(latest).extracting(PdfExtractionJobView::id).containsExactly(newer);
        assertThat(older).isNotEqualTo(newer);
    }

    @Test
    void 진행_중_작업은_재시도_대기도_포함하고_끝난_작업은_뺀다() {
        finished(ANNOUNCEMENT, "FAILED", "2026-11-01T03:00:00Z");
        long retrying = finished(ANNOUNCEMENT, "RETRY", null);

        var active = jobs.findViewsByStatusIn(PdfExtractionJob.KIND, ANNOUNCEMENT, ACTIVE, Limit.of(1));

        assertThat(active).extracting(PdfExtractionJobView::id).containsExactly(retrying);
        assertThat(jobs.existsByKindAndAnnouncementIdAndStatusIn(PdfExtractionJob.KIND, ANNOUNCEMENT, ACTIVE))
                .isTrue();
    }

    @Test
    void 입력_스냅숏은_문자열로_감싸지_않고_jsonb_객체로_저장한다() {
        // 문자열로 한 번 더 감싸면 워커가 input을 객체로 읽지 못한다.
        PdfExtractionJob job = jobs.saveAndFlush(PdfExtractionJob.requestedByAdmin(ANNOUNCEMENT, "admin",
                PdfExtractionSource.of("a.pdf", new byte[] {1}), "{\"sourceSupplyRows\":[]}",
                Instant.parse("2026-11-01T00:00:00Z")));

        Object[] stored = (Object[]) entityManager.createNativeQuery("""
                SELECT jsonb_typeof(input), jsonb_typeof(input -> 'sourceSupplyRows') FROM ai_jobs WHERE id = :id
                """).setParameter("id", job.getId()).getSingleResult();

        assertThat(stored).containsExactly("object", "array");
    }

    @Test
    void 진행_중_작업_index_위반은_그_제약_이름으로_드러난다() {
        // 테스트 스키마는 JPA가 만들어 부분 index가 없다. 같은 정의로 이 트랜잭션 안에서만 만든다.
        entityManager.createNativeQuery("""
                CREATE UNIQUE INDEX ux_ai_jobs_active ON ai_jobs (kind, announcement_id)
                WHERE status IN ('PENDING', 'RUNNING', 'RETRY')
                """).executeUpdate();
        jobs.saveAndFlush(job(ANNOUNCEMENT));

        assertThatThrownBy(() -> jobs.saveAndFlush(job(ANNOUNCEMENT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .cause().isInstanceOf(ConstraintViolationException.class)
                .extracting("constraintName").isEqualTo("ux_ai_jobs_active");
    }

    @Test
    void 진행_중_작업을_끝내면_PDF와_lease를_비워_처리_중인_워커의_완료_쓰기가_0행이_된다() {
        // 운영 스키마의 상태 제약을 이 트랜잭션 안에서만 만들어, 끝낸 행이 제약을 지키는지 함께 본다.
        entityManager.createNativeQuery("""
                ALTER TABLE ai_jobs
                    ADD CONSTRAINT ck_pdf_cleared CHECK (status NOT IN ('SUCCEEDED', 'FAILED') OR pdf IS NULL),
                    ADD CONSTRAINT ck_failure CHECK (status <> 'FAILED' OR error_code IS NOT NULL),
                    ADD CONSTRAINT ck_completed CHECK ((status IN ('SUCCEEDED', 'FAILED')) = (completed_at IS NOT NULL)),
                    ADD CONSTRAINT ck_lease CHECK (status <> 'RUNNING' OR (lease_token IS NOT NULL AND lease_until IS NOT NULL))
                """).executeUpdate();
        long running = jobs.saveAndFlush(job(ANNOUNCEMENT)).getId();
        entityManager.createNativeQuery("""
                UPDATE ai_jobs SET status = 'RUNNING', attempts = 1, lease_token = CAST('00000000-0000-0000-0000-000000000001' AS uuid),
                    lease_until = CAST('2026-11-01T00:03:00Z' AS timestamptz) WHERE id = :id
                """).setParameter("id", running).executeUpdate();
        long done = finished(ANNOUNCEMENT, "SUCCEEDED", "2026-11-01T01:00:00Z");
        Instant now = Instant.parse("2026-11-01T00:10:00Z");

        assertThat(jobs.failActive(PdfExtractionJob.KIND, ANNOUNCEMENT, done, ACTIVE, PdfExtractionJobStatus.FAILED,
                "CANCELLED_BY_ADMIN", "취소", now)).isZero();
        assertThat(jobs.failActive(PdfExtractionJob.KIND, ANNOUNCEMENT + 1, running, ACTIVE,
                PdfExtractionJobStatus.FAILED, "CANCELLED_BY_ADMIN", "취소", now)).isZero();
        assertThat(jobs.failActive(PdfExtractionJob.KIND, ANNOUNCEMENT, running, ACTIVE, PdfExtractionJobStatus.FAILED,
                "CANCELLED_BY_ADMIN", "취소", now)).isOne();

        Object[] row = (Object[]) entityManager.createNativeQuery("""
                SELECT status, pdf IS NULL, lease_token IS NULL, error_code, completed_at = CAST('2026-11-01T00:10:00Z' AS timestamptz)
                FROM ai_jobs WHERE id = :id
                """).setParameter("id", running).getSingleResult();
        assertThat(row).containsExactly("FAILED", true, true, "CANCELLED_BY_ADMIN", true);
        // 워커(jobs.complete)는 자기 lease_token일 때만 쓴다.
        int workerWrite = entityManager.createNativeQuery("""
                UPDATE ai_jobs SET status = 'SUCCEEDED', result = CAST('{}' AS jsonb)
                WHERE id = :id AND lease_token = CAST('00000000-0000-0000-0000-000000000001' AS uuid)
                """).setParameter("id", running).executeUpdate();
        assertThat(workerWrite).isZero();
    }

    @Test
    void 마감_전에_넣은_진행_중_작업만_끝낸다() {
        long old = jobs.saveAndFlush(PdfExtractionJob.requestedByAdmin(ANNOUNCEMENT, "admin",
                PdfExtractionSource.of("a.pdf", new byte[] {1}), "{}", Instant.parse("2026-11-01T00:00:00Z"))).getId();
        long other = jobs.saveAndFlush(PdfExtractionJob.requestedByAdmin(ANNOUNCEMENT + 1, "admin",
                PdfExtractionSource.of("a.pdf", new byte[] {1}), "{}", Instant.parse("2026-11-01T00:00:00Z"))).getId();
        Instant now = Instant.parse("2026-11-01T00:40:00Z");

        assertThat(jobs.failActiveCreatedBefore(PdfExtractionJob.KIND, ANNOUNCEMENT, Instant.parse("2026-10-31T23:59:59Z"),
                ACTIVE, PdfExtractionJobStatus.FAILED, "DEADLINE_EXCEEDED", "마감", now)).isZero();
        assertThat(jobs.failActiveCreatedBefore(PdfExtractionJob.KIND, ANNOUNCEMENT, Instant.parse("2026-11-01T00:00:00Z"),
                ACTIVE, PdfExtractionJobStatus.FAILED, "DEADLINE_EXCEEDED", "마감", now)).isOne();

        assertThat(jobs.findView(PdfExtractionJob.KIND, ANNOUNCEMENT, old)).get()
                .extracting(PdfExtractionJobView::status, PdfExtractionJobView::errorCode)
                .containsExactly(PdfExtractionJobStatus.FAILED, "DEADLINE_EXCEEDED");
        assertThat(jobs.findView(PdfExtractionJob.KIND, ANNOUNCEMENT + 1, other)).get()
                .extracting(PdfExtractionJobView::status).isEqualTo(PdfExtractionJobStatus.PENDING);
    }

    @Test
    void 큐_요약은_대기_재시도_처리중_수와_가장_오래된_대기와_기간_안에_끝난_작업만_센다() {
        Instant since = Instant.parse("2026-11-01T00:00:00Z");
        long oldWaiting = jobs.saveAndFlush(PdfExtractionJob.requestedByAdmin(ANNOUNCEMENT, "admin",
                PdfExtractionSource.of("a.pdf", new byte[] {1}), "{}", Instant.parse("2026-11-01T05:00:00Z"))).getId();
        jobs.saveAndFlush(PdfExtractionJob.requestedByAdmin(ANNOUNCEMENT + 1, "admin",
                PdfExtractionSource.of("a.pdf", new byte[] {1}), "{}", Instant.parse("2026-11-01T06:00:00Z")));
        long retrying = finished(ANNOUNCEMENT + 2, "RETRY", null);
        long running = finished(ANNOUNCEMENT + 3, "RUNNING", null);
        started(finished(ANNOUNCEMENT + 4, "SUCCEEDED", "2026-11-01T01:00:20Z"), "2026-11-01T01:00:00Z");
        started(finished(ANNOUNCEMENT + 5, "SUCCEEDED", "2026-11-01T02:01:00Z"), "2026-11-01T02:00:00Z");
        started(finished(ANNOUNCEMENT + 6, "SUCCEEDED", "2026-10-31T23:59:00Z"), "2026-10-31T23:00:00Z");
        finished(ANNOUNCEMENT + 7, "FAILED", "2026-11-01T03:00:00Z");
        finished(ANNOUNCEMENT + 8, "FAILED", "2026-10-31T12:00:00Z");

        var summary = jobs.summarizeQueue(PdfExtractionJob.KIND, since);

        assertThat(summary.getPending()).isEqualTo(2);
        assertThat(summary.getRetrying()).isEqualTo(1);
        assertThat(summary.getRunning()).isEqualTo(1);
        // 재시도 대기 작업은 finished 도움 함수가 2026-11-01T00:00Z에 넣어 가장 오래 기다렸다.
        assertThat(summary.getOldestWaitingSince()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(summary.getSucceeded()).isEqualTo(2);
        assertThat(summary.getFailed()).isEqualTo(1);
        assertThat(summary.getAverageProcessingSeconds()).isEqualTo(40.0);
        assertThat(summary.getMaxProcessingSeconds()).isEqualTo(60.0);
        assertThat(List.of(oldWaiting, retrying, running)).doesNotHaveDuplicates();
    }

    @Test
    void 작업이_없으면_큐_요약은_0이고_시각과_처리_시간은_비어_있다() {
        var summary = jobs.summarizeQueue(PdfExtractionJob.KIND, Instant.parse("2026-11-01T00:00:00Z"));

        assertThat(summary.getPending() + summary.getRetrying() + summary.getRunning()).isZero();
        assertThat(summary.getOldestWaitingSince()).isNull();
        assertThat(summary.getAverageProcessingSeconds()).isNull();
    }

    @Test
    void 앞선_작업은_처리_중인_작업과_워커가_먼저_꺼낼_작업만_센다() {
        // now 01:00, 꺼내기 기한은 넣은 지 30분이다. 아래 순서대로 넣어 id 순서가 곧 꺼내는 순서다.
        Instant now = Instant.parse("2026-11-01T01:00:00Z");
        queued(ANNOUNCEMENT, "PENDING", "00:20", "00:20", null, 0);
        queued(ANNOUNCEMENT + 1, "RETRY", "00:40", "01:05", null, 1);
        queued(ANNOUNCEMENT + 2, "RETRY", "00:40", "00:59", null, 1);
        queued(ANNOUNCEMENT + 3, "RUNNING", "00:10", "00:10", "01:02", 1);
        queued(ANNOUNCEMENT + 4, "RUNNING", "00:45", "00:45", "00:58", 1);
        queued(ANNOUNCEMENT + 5, "RUNNING", "00:45", "00:45", "00:58", 3);
        long otherKind = queued(ANNOUNCEMENT + 6, "PENDING", "00:50", "00:50", null, 0);
        entityManager.createNativeQuery("UPDATE ai_jobs SET kind = 'OTHER_EXTRACTION' WHERE id = :id")
                .setParameter("id", otherKind).executeUpdate();
        finished(ANNOUNCEMENT + 7, "SUCCEEDED", "2026-11-01T00:57:00Z");
        long target = queued(ANNOUNCEMENT + 8, "PENDING", "00:55", "00:55", null, 0);
        queued(ANNOUNCEMENT + 9, "PENDING", "00:56", "00:56", null, 0);

        long ahead = jobs.countAhead(target, now, now.minus(Duration.ofMinutes(30)), 3);

        // 꺼내기 기한이 지난 대기, 재시도 시각 전, 시도를 다 쓴 lease 만료, 끝난 작업과 뒤에 넣은 작업은 빠진다.
        // 처리 중(lease 유효), 재시도 시각이 된 재시도, 다시 꺼낼 lease 만료, 다른 종류의 대기가 남는다.
        assertThat(ahead).isEqualTo(4);
    }

    private static PdfExtractionJob job(long announcementId) {
        return PdfExtractionJob.requestedByAdmin(announcementId, "admin",
                PdfExtractionSource.of("a.pdf", new byte[] {1}), "{}", Instant.parse("2026-11-01T00:00:00Z"));
    }

    // 상태 전이는 워커가 쓰므로 테스트도 SQL로 바꾼다.
    private long finished(long announcementId, String status, String completedAt) {
        PdfExtractionJob job = jobs.saveAndFlush(job(announcementId));
        String result = null;
        String errorCode = null;
        if (status.equals("SUCCEEDED")) {
            result = "{}";
        }
        if (status.equals("FAILED")) {
            errorCode = "MODEL_UNAVAILABLE";
        }
        entityManager.createNativeQuery("""
                UPDATE ai_jobs SET status = :status, completed_at = CAST(:completedAt AS timestamptz),
                    pdf = CASE WHEN CAST(:completedAt AS timestamptz) IS NULL THEN pdf END,
                    result = CAST(:result AS jsonb), error_code = :errorCode, attempts = 1 WHERE id = :id
                """)
                .setParameter("status", status)
                .setParameter("completedAt", completedAt)
                .setParameter("result", result)
                .setParameter("errorCode", errorCode)
                .setParameter("id", job.getId())
                .executeUpdate();
        entityManager.clear();
        return job.getId();
    }

    /** 시각은 2026-11-01의 시:분이다. 워커의 꺼내기 조건에 쓰는 칸을 SQL로 직접 맞춘다. */
    private long queued(long announcementId, String status, String createdAt, String nextAttemptAt, String leaseUntil,
            int attempts) {
        PdfExtractionJob job = jobs.saveAndFlush(job(announcementId));
        String lease = null;
        if (leaseUntil != null) {
            lease = "2026-11-01T" + leaseUntil + ":00Z";
        }
        entityManager.createNativeQuery("""
                UPDATE ai_jobs SET status = :status, created_at = CAST(:createdAt AS timestamptz),
                    next_attempt_at = CAST(:nextAttemptAt AS timestamptz),
                    lease_until = CAST(:leaseUntil AS timestamptz), attempts = :attempts
                WHERE id = :id
                """)
                .setParameter("status", status)
                .setParameter("createdAt", "2026-11-01T" + createdAt + ":00Z")
                .setParameter("nextAttemptAt", "2026-11-01T" + nextAttemptAt + ":00Z")
                .setParameter("leaseUntil", lease)
                .setParameter("attempts", attempts)
                .setParameter("id", job.getId())
                .executeUpdate();
        entityManager.clear();
        return job.getId();
    }

    private void started(long id, String startedAt) {
        entityManager.createNativeQuery("UPDATE ai_jobs SET started_at = CAST(:startedAt AS timestamptz) WHERE id = :id")
                .setParameter("startedAt", startedAt).setParameter("id", id).executeUpdate();
    }
}
