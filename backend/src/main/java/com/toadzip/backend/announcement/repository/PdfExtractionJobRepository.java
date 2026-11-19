package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.PdfExtractionJob;
import com.toadzip.backend.announcement.domain.PdfExtractionJobStatus;
import com.toadzip.backend.announcement.domain.PdfExtractionJobView;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface PdfExtractionJobRepository extends JpaRepository<PdfExtractionJob, Long> {
    String VIEW = """
            SELECT new com.toadzip.backend.announcement.domain.PdfExtractionJobView(job.id, job.status, job.fileName,
                job.pdfSha256, job.attempts, job.createdAt, job.completedAt, job.errorCode, job.errorMessage, job.result)
            FROM PdfExtractionJob job
            WHERE job.kind = :kind AND job.announcementId = :announcementId
            """;

    boolean existsByKindAndAnnouncementIdAndStatusIn(
            String kind, long announcementId, Collection<PdfExtractionJobStatus> statuses);

    @Query(VIEW + " AND job.id = :id")
    Optional<PdfExtractionJobView> findView(String kind, long announcementId, long id);

    @Query(VIEW + " AND job.status IN :statuses ORDER BY job.id")
    List<PdfExtractionJobView> findViewsByStatusIn(String kind, long announcementId,
            Collection<PdfExtractionJobStatus> statuses, Limit limit);

    /**
     * 진행 중인 작업을 FAILED로 끝내고 PDF를 비운다. 워커 jobs.fail_overdue와 같이 lease를 비우므로,
     * 그 작업을 처리 중인 워커의 완료·재시도 쓰기는 lease_token 조건에 걸려 0행이 된다.
     */
    String FAIL_ACTIVE = """
            UPDATE PdfExtractionJob job SET job.status = :failed, job.pdf = NULL, job.errorCode = :code,
                job.errorMessage = :message, job.leaseToken = NULL, job.leaseUntil = NULL, job.completedAt = :now
            WHERE job.kind = :kind AND job.announcementId = :announcementId AND job.status IN :active
            """;

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(FAIL_ACTIVE + " AND job.id = :id")
    int failActive(String kind, long announcementId, long id, Collection<PdfExtractionJobStatus> active,
            PdfExtractionJobStatus failed, String code, String message, Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(FAIL_ACTIVE + " AND job.createdAt <= :createdBefore")
    int failActiveCreatedBefore(String kind, long announcementId, Instant createdBefore,
            Collection<PdfExtractionJobStatus> active, PdfExtractionJobStatus failed, String code, String message,
            Instant now);

    @Query(VIEW + " AND job.status = :status ORDER BY job.completedAt DESC, job.id DESC")
    List<PdfExtractionJobView> findLatestViews(String kind, long announcementId, PdfExtractionJobStatus status,
            Limit limit);

    /**
     * 큐 요약. 대기는 PENDING·RETRY이고 가장 오래된 대기는 넣은 시각으로 잰다. 성공·실패와 처리 시간은 since 이후에 끝난 작업만
     * 센다. 처리 시간은 워커가 처음 꺼낸 때(started_at, 재시도해도 바뀌지 않음)부터 성공한 때까지라 재시도 대기가 들어간다.
     */
    @Query(value = """
            SELECT COUNT(*) FILTER (WHERE status = 'PENDING') AS pending,
                COUNT(*) FILTER (WHERE status = 'RETRY') AS retrying,
                COUNT(*) FILTER (WHERE status = 'RUNNING') AS running,
                MIN(created_at) FILTER (WHERE status IN ('PENDING', 'RETRY')) AS oldestWaitingSince,
                COUNT(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
                COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
                AVG(EXTRACT(EPOCH FROM completed_at - started_at)) FILTER (WHERE status = 'SUCCEEDED')
                    AS averageProcessingSeconds,
                MAX(EXTRACT(EPOCH FROM completed_at - started_at)) FILTER (WHERE status = 'SUCCEEDED')
                    AS maxProcessingSeconds
            FROM ai_jobs
            WHERE kind = :kind AND (status IN ('PENDING', 'RETRY', 'RUNNING') OR completed_at >= :since)
            """, nativeQuery = true)
    QueueSummary summarizeQueue(String kind, Instant since);

    /**
     * id 작업보다 먼저 처리될 작업 수다. 지금 처리 중인 작업(lease가 살아 있는 RUNNING)과, 워커가 이 작업보다 먼저 꺼낼 작업을
     * 센다. 워커는 종류를 가리지 않고 관리자 작업(ADMIN)을 먼저, 같은 출처끼리는 id 순서로 꺼내므로 다른 종류의 작업도 센다.
     * 먼저 꺼낼 작업의 조건과 순서는 ai-worker jobs.claim과 같아야 하며, 한쪽을 바꾸면 다른 쪽도 바꾼다.
     */
    @Query(value = """
            SELECT COUNT(*) FROM ai_jobs other, ai_jobs me
            WHERE me.id = :id AND other.id <> me.id
              AND ((other.status = 'RUNNING' AND other.lease_until > :now)
                OR ((other.request_source <> 'ADMIN', other.id) < (me.request_source <> 'ADMIN', me.id)
                    AND other.created_at > :claimableSince
                    AND ((other.status IN ('PENDING', 'RETRY') AND other.next_attempt_at <= :now)
                        OR (other.status = 'RUNNING' AND other.lease_until <= :now
                            AND other.attempts < :maxAttempts))))
            """, nativeQuery = true)
    long countAhead(long id, Instant now, Instant claimableSince, int maxAttempts);

    long countByKindAndRequestSourceAndStatusIn(String kind, String requestSource,
            Collection<PdfExtractionJobStatus> statuses);

    /**
     * 공고의 기다리는 서버 작업(BATCH)을 관리자 작업으로 바꾼다. 관리자가 같은 PDF를 요청하면 그 작업이 먼저 꺼내지고 일반 처리로
     * 불린다. 이미 처리 중인 작업과 다른 PDF의 작업은 그대로 둔다.
     */
    String PROMOTE_WAITING_BATCH = """
            UPDATE ai_jobs SET request_source = 'ADMIN', requested_by = :admin
            WHERE kind = :kind AND announcement_id = :announcementId AND request_source = 'BATCH'
              AND status IN ('PENDING', 'RETRY')
            """;

    /** 관리자가 올린 PDF와 해시가 같은 기다리는 서버 작업을 관리자 작업으로 바꾼다. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = PROMOTE_WAITING_BATCH + " AND pdf_sha256 = :pdfSha256", nativeQuery = true)
    int promoteWaitingBatchOfPdf(String kind, long announcementId, String pdfSha256, String admin);

    /**
     * 관리자가 다른 PDF를 요청했을 때 그 공고의 진행 중 서버 작업(BATCH)을 끝낸다. 관리자가 서버 작업이 끝날 때까지 기다리지 않게
     * 한다. 처리 중인 작업도 끝내며, lease를 비우므로 그 워커의 이후 쓰기는 0행이 된다.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(FAIL_ACTIVE + " AND job.requestSource = :batch AND job.pdfSha256 <> :pdfSha256")
    int failActiveBatchOfOtherPdf(String kind, long announcementId, String pdfSha256, String batch,
            Collection<PdfExtractionJobStatus> active, PdfExtractionJobStatus failed, String code, String message,
            Instant now);

    boolean existsByKindAndAnnouncementIdAndRequestSourceAndStatusIn(String kind, long announcementId,
            String requestSource, Collection<PdfExtractionJobStatus> statuses);

    interface QueueSummary {
        long getPending();

        long getRetrying();

        long getRunning();

        Instant getOldestWaitingSince();

        long getSucceeded();

        long getFailed();

        Double getAverageProcessingSeconds();

        Double getMaxProcessingSeconds();
    }
}
