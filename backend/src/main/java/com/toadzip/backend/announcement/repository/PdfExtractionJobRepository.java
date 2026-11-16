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
