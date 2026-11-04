package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.PdfExtractionInput;
import com.toadzip.backend.announcement.domain.PdfExtractionJob;
import com.toadzip.backend.announcement.domain.PdfExtractionJobStatus;
import com.toadzip.backend.announcement.domain.PdfExtractionJobView;
import com.toadzip.backend.announcement.domain.PdfExtractionSource;
import com.toadzip.backend.announcement.domain.TemporaryAttachment;
import com.toadzip.backend.announcement.dto.response.AnnouncementPdfExtraction;
import com.toadzip.backend.announcement.dto.response.AttachmentContent;
import com.toadzip.backend.announcement.dto.response.PdfExtractionJobResponse;
import com.toadzip.backend.announcement.dto.response.SavedAnnouncementPdfExtraction;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementPdfException;
import com.toadzip.backend.announcement.exception.PdfExtractionInProgressException;
import com.toadzip.backend.announcement.exception.PdfExtractionJobNotFoundException;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.PdfExtractionJobRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 공고문 PDF 추출을 작업(ai_jobs)으로 넣고 상태와 결과를 읽는다. 추출은 Python AI 워커가 하고, 공고 데이터에는 반영하지 않는다.
 * 같은 공고에 진행 중 작업이 있으면 새로 넣지 않아 유료 호출이 겹치지 않게 한다.
 */
@Slf4j
@Service
public class AnnouncementPdfExtractionService {
    public static final long MAX_PDF_BYTES = 30L * 1024 * 1024;
    private static final Set<PdfExtractionJobStatus> ACTIVE = EnumSet.of(
            PdfExtractionJobStatus.PENDING, PdfExtractionJobStatus.RUNNING, PdfExtractionJobStatus.RETRY);
    private static final String ACTIVE_JOB_INDEX = "ux_ai_jobs_active";
    /** 워커의 마감(ai-worker worker.DEADLINE)과 같다. 워커가 멈춰도 오래된 작업이 끝나지 않은 채 새 추출을 막지 않게 한다. */
    static final Duration DEADLINE = Duration.ofMinutes(40);
    static final String DEADLINE_EXCEEDED_CODE = "DEADLINE_EXCEEDED";
    static final String DEADLINE_EXCEEDED_MESSAGE = "요청한 지 오래되어 작업을 끝냈습니다. 다시 요청해 주세요.";
    static final String CANCELLED_CODE = "CANCELLED_BY_ADMIN";
    static final String CANCELLED_MESSAGE = "관리자가 추출을 취소했습니다. 다시 요청할 수 있습니다.";

    private final AnnouncementRepository announcements;
    private final PdfExtractionJobRepository jobs;
    private final PdfExtractionInputs inputs;
    private final AnnouncementAttachmentService attachments;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AnnouncementPdfExtractionService(
            AnnouncementRepository announcements,
            PdfExtractionJobRepository jobs,
            PdfExtractionInputs inputs,
            AnnouncementAttachmentService attachments,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.announcements = announcements;
        this.jobs = jobs;
        this.inputs = inputs;
        this.attachments = attachments;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public PdfExtractionJobResponse request(long announcementId, String admin, String fileName, byte[] pdf) {
        requireAnnouncement(announcementId);
        AnnouncementPdfDocument.requireReadable(pdf);
        failOverdue(announcementId);
        requireNoActiveJob(announcementId);
        PdfExtractionInput input = inputs.read(announcementId);
        PdfExtractionJob job = PdfExtractionJob.requestedByAdmin(announcementId, admin,
                PdfExtractionSource.of(fileName, pdf), objectMapper.writeValueAsString(input), clock.instant());
        PdfExtractionJob saved = save(job);
        log.info("event=announcement.pdf_extraction.requested announcementId={} jobId={} lhSupplyRows={}",
                announcementId, saved.getId(), input.lhSupplyRows().size());
        return response(PdfExtractionJobView.of(saved));
    }

    /** 공고 첨부 PDF를 서버가 받아 작업을 넣는다. 진행 중 작업이 있으면 내려받지 않는다. */
    public PdfExtractionJobResponse requestAttachment(long announcementId, String admin, long attachmentId) {
        requireAnnouncement(announcementId);
        failOverdue(announcementId);
        requireNoActiveJob(announcementId);
        AttachmentContent content = attachments.readForAdmin(announcementId, attachmentId);
        return request(announcementId, admin, content.fileName(), bytesOf(content.file()));
    }

    private static byte[] bytesOf(TemporaryAttachment file) {
        try (file) {
            if (file.size() > MAX_PDF_BYTES) {
                throw new InvalidAnnouncementPdfException("공고문 PDF는 30MB 이하만 추출할 수 있습니다.");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) file.size());
            file.transferTo(bytes);
            return bytes.toByteArray();
        }
        catch (IOException exception) {
            throw new AttachmentUnavailableException(AttachmentUnavailableException.Reason.UPSTREAM_FAILURE);
        }
    }

    public PdfExtractionJobResponse job(long announcementId, long jobId) {
        requireAnnouncement(announcementId);
        failOverdue(announcementId);
        return jobs.findView(PdfExtractionJob.KIND, announcementId, jobId)
                .map(this::response)
                .orElseThrow(PdfExtractionJobNotFoundException::new);
    }

    /** 진행 중인 작업. 없으면 null이다. */
    public PdfExtractionJobResponse active(long announcementId) {
        requireAnnouncement(announcementId);
        failOverdue(announcementId);
        return jobs.findViewsByStatusIn(PdfExtractionJob.KIND, announcementId, ACTIVE, Limit.of(1)).stream()
                .findFirst()
                .map(this::response)
                .orElse(null);
    }

    /**
     * 관리자가 진행 중인 추출을 끝낸다. 워커가 작업을 가져가지 않아도 운영자가 기다림을 멈추고 다시 요청할 수 있게 한다.
     * 이미 끝난 작업은 그대로 두고 현재 상태를 돌려준다.
     */
    public PdfExtractionJobResponse cancel(long announcementId, long jobId) {
        requireAnnouncement(announcementId);
        if (jobs.failActive(PdfExtractionJob.KIND, announcementId, jobId, ACTIVE, PdfExtractionJobStatus.FAILED,
                CANCELLED_CODE, CANCELLED_MESSAGE, clock.instant()) > 0) {
            log.info("event=announcement.pdf_extraction.cancelled announcementId={} jobId={}", announcementId, jobId);
        }
        return job(announcementId, jobId);
    }

    /** 가장 최근에 성공한 추출 결과. 없으면 null이다. */
    public SavedAnnouncementPdfExtraction latest(long announcementId) {
        requireAnnouncement(announcementId);
        return jobs.findLatestViews(PdfExtractionJob.KIND, announcementId, PdfExtractionJobStatus.SUCCEEDED,
                        Limit.of(1)).stream()
                .findFirst()
                .map(job -> new SavedAnnouncementPdfExtraction(job.fileName(), job.pdfSha256(), job.completedAt(),
                        extraction(job)))
                .orElse(null);
    }

    // 동시에 들어온 두 요청은 진행 중 작업 unique index가 하나만 받는다. 다른 제약 위반은 그대로 올린다.
    private PdfExtractionJob save(PdfExtractionJob job) {
        try {
            return jobs.saveAndFlush(job);
        }
        catch (DataIntegrityViolationException exception) {
            if (violates(exception, ACTIVE_JOB_INDEX)) {
                throw new PdfExtractionInProgressException();
            }
            throw exception;
        }
    }

    private static boolean violates(Throwable exception, String constraint) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraint.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /** 넣은 지 마감이 지난 진행 중 작업을 실패로 끝낸다. 워커가 돌면 워커가 먼저 끝내므로 0행이다. */
    private void failOverdue(long announcementId) {
        Instant now = clock.instant();
        int overdue = jobs.failActiveCreatedBefore(PdfExtractionJob.KIND, announcementId, now.minus(DEADLINE), ACTIVE,
                PdfExtractionJobStatus.FAILED, DEADLINE_EXCEEDED_CODE, DEADLINE_EXCEEDED_MESSAGE, now);
        if (overdue > 0) {
            log.warn("event=announcement.pdf_extraction.overdue announcementId={} jobs={}", announcementId, overdue);
        }
    }

    private void requireNoActiveJob(long announcementId) {
        if (jobs.existsByKindAndAnnouncementIdAndStatusIn(PdfExtractionJob.KIND, announcementId, ACTIVE)) {
            throw new PdfExtractionInProgressException();
        }
    }

    private void requireAnnouncement(long announcementId) {
        if (!announcements.existsById(announcementId)) {
            throw new AnnouncementNotFoundException();
        }
    }

    private PdfExtractionJobResponse response(PdfExtractionJobView job) {
        return new PdfExtractionJobResponse(job.id(), job.status().name(), job.fileName(), job.pdfSha256(),
                job.attempts(), job.createdAt(), job.completedAt(), failure(job), retryReason(job), extraction(job));
    }

    private static PdfExtractionJobResponse.Failure failure(PdfExtractionJobView job) {
        if (job.status() != PdfExtractionJobStatus.FAILED) {
            return null;
        }
        return new PdfExtractionJobResponse.Failure(job.errorCode(), job.errorMessage());
    }

    private static PdfExtractionJobResponse.Failure retryReason(PdfExtractionJobView job) {
        if (job.status() != PdfExtractionJobStatus.RETRY || job.errorCode() == null) {
            return null;
        }
        return new PdfExtractionJobResponse.Failure(job.errorCode(), job.errorMessage());
    }

    private AnnouncementPdfExtraction extraction(PdfExtractionJobView job) {
        if (job.result() == null) {
            return null;
        }
        return objectMapper.readValue(job.result(), AnnouncementPdfExtraction.class);
    }
}
