package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.PdfExtractionJob;
import com.toadzip.backend.announcement.domain.PdfExtractionJobStatus;
import com.toadzip.backend.announcement.domain.PdfExtractionJobView;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    @Query(VIEW + " AND job.status = :status ORDER BY job.completedAt DESC, job.id DESC")
    List<PdfExtractionJobView> findLatestViews(String kind, long announcementId, PdfExtractionJobStatus status,
            Limit limit);
}
