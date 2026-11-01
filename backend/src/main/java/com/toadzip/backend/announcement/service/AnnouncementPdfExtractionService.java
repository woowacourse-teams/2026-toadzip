package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.PdfExtractionInput;
import com.toadzip.backend.announcement.domain.PdfExtractionJob;
import com.toadzip.backend.announcement.domain.PdfExtractionJobStatus;
import com.toadzip.backend.announcement.domain.PdfExtractionJobView;
import com.toadzip.backend.announcement.domain.PdfExtractionSource;
import com.toadzip.backend.announcement.dto.response.AnnouncementPdfExtraction;
import com.toadzip.backend.announcement.dto.response.PdfExtractionJobResponse;
import com.toadzip.backend.announcement.dto.response.SavedAnnouncementPdfExtraction;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.exception.PdfExtractionInProgressException;
import com.toadzip.backend.announcement.exception.PdfExtractionJobNotFoundException;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.PdfExtractionInputQueryRepository;
import com.toadzip.backend.announcement.repository.PdfExtractionJobRepository;
import java.time.Clock;
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
    private static final Set<PdfExtractionJobStatus> ACTIVE = EnumSet.of(
            PdfExtractionJobStatus.PENDING, PdfExtractionJobStatus.RUNNING, PdfExtractionJobStatus.RETRY);
    private static final String ACTIVE_JOB_INDEX = "ux_ai_jobs_active";

    private final AnnouncementRepository announcements;
    private final PdfExtractionJobRepository jobs;
    private final PdfExtractionInputQueryRepository inputs;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AnnouncementPdfExtractionService(
            AnnouncementRepository announcements,
            PdfExtractionJobRepository jobs,
            PdfExtractionInputQueryRepository inputs,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.announcements = announcements;
        this.jobs = jobs;
        this.inputs = inputs;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public PdfExtractionJobResponse request(long announcementId, String admin, String fileName, byte[] pdf) {
        requireAnnouncement(announcementId);
        AnnouncementPdfDocument.read(pdf);
        if (jobs.existsByKindAndAnnouncementIdAndStatusIn(PdfExtractionJob.KIND, announcementId, ACTIVE)) {
            throw new PdfExtractionInProgressException();
        }
        PdfExtractionInput input = inputs.read(announcementId);
        PdfExtractionJob job = PdfExtractionJob.requestedByAdmin(announcementId, admin,
                PdfExtractionSource.of(fileName, pdf), objectMapper.writeValueAsString(input), clock.instant());
        PdfExtractionJob saved = save(job);
        log.info("event=announcement.pdf_extraction.requested announcementId={} jobId={} lhSupplyRows={} "
                        + "housingTypes={}", announcementId, saved.getId(), input.lhSupplyRows().size(),
                input.housingTypes().size());
        return response(PdfExtractionJobView.of(saved));
    }

    public PdfExtractionJobResponse job(long announcementId, long jobId) {
        requireAnnouncement(announcementId);
        return jobs.findView(PdfExtractionJob.KIND, announcementId, jobId)
                .map(this::response)
                .orElseThrow(PdfExtractionJobNotFoundException::new);
    }

    /** 진행 중인 작업. 없으면 null이다. */
    public PdfExtractionJobResponse active(long announcementId) {
        requireAnnouncement(announcementId);
        return jobs.findViewsByStatusIn(PdfExtractionJob.KIND, announcementId, ACTIVE, Limit.of(1)).stream()
                .findFirst()
                .map(this::response)
                .orElse(null);
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

    private void requireAnnouncement(long announcementId) {
        if (!announcements.existsById(announcementId)) {
            throw new AnnouncementNotFoundException();
        }
    }

    private PdfExtractionJobResponse response(PdfExtractionJobView job) {
        return new PdfExtractionJobResponse(job.id(), job.status().name(), job.fileName(), job.pdfSha256(),
                job.attempts(), job.createdAt(), job.completedAt(), failure(job), extraction(job));
    }

    private static PdfExtractionJobResponse.Failure failure(PdfExtractionJobView job) {
        if (job.status() != PdfExtractionJobStatus.FAILED) {
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
