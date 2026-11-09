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
}
