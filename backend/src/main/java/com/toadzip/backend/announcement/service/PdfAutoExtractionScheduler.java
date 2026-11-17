package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.PdfExtractionJob;
import com.toadzip.backend.announcement.domain.PdfExtractionJobStatus;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementPdfException;
import com.toadzip.backend.announcement.exception.PdfExtractionInProgressException;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementSearchRepository;
import com.toadzip.backend.announcement.repository.PdfExtractionJobRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 마감 전 공고 중 공고문 PDF가 있는데 추출한 적이 없는 공고에 서버 추출 작업(BATCH)을 넣는다. 결과는 관리자가 PDF 보정 화면에서
 * 검토해 적용할 때만 공고에 반영된다. 유료 호출이 한꺼번에 몰리지 않고 마감(40분) 안에 끝나도록 진행 중인 서버 작업 수를 제한한다.
 * 공고마다 한 번만 넣는다. 실패한 작업은 다시 넣지 않고 운영 홈의 '추출 실패'로 관리자에게 맡긴다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "announcement.pdf-auto-extraction.enabled", havingValue = "true")
public class PdfAutoExtractionScheduler {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Set<PdfExtractionJobStatus> ACTIVE = EnumSet.of(
            PdfExtractionJobStatus.PENDING, PdfExtractionJobStatus.RUNNING, PdfExtractionJobStatus.RETRY);
    /** 처리 단계 식(AnnouncementProcessingStageSql)의 PDF 첨부 기준과 같다. */
    private static final Pattern PDF = Pattern.compile("\\.pdf($|[\\s?])", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOTICE_NAME = Pattern.compile("공고문|모집공고");
    /** 한 번에 살펴볼 후보 수. 받을 수 없는 첨부를 건너뛰고도 빈자리를 채울 만큼 읽는다. */
    private static final int CANDIDATE_LIMIT = 50;
    private static final Set<AttachmentUnavailableException.Reason> RETRYABLE = EnumSet.of(
            AttachmentUnavailableException.Reason.UPSTREAM_FAILURE, AttachmentUnavailableException.Reason.BUSY);

    private final AnnouncementPdfExtractionService extractions;
    private final PdfExtractionJobRepository jobs;
    private final AnnouncementSearchRepository announcements;
    private final AnnouncementAttachmentRepository attachments;
    private final Clock clock;
    private final int maxInFlight;
    /** 첨부가 PDF가 아니거나 없어 다시 시도해도 같은 공고. 서버를 다시 띄우면 한 번 더 본다. */
    private final Set<Long> unavailable = ConcurrentHashMap.newKeySet();

    public PdfAutoExtractionScheduler(
            AnnouncementPdfExtractionService extractions,
            PdfExtractionJobRepository jobs,
            AnnouncementSearchRepository announcements,
            AnnouncementAttachmentRepository attachments,
            Clock clock,
            @Value("${announcement.pdf-auto-extraction.max-in-flight}") int maxInFlight
    ) {
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("announcement.pdf-auto-extraction.max-in-flight는 1 이상이어야 합니다.");
        }
        this.extractions = extractions;
        this.jobs = jobs;
        this.announcements = announcements;
        this.attachments = attachments;
        this.clock = clock;
        this.maxInFlight = maxInFlight;
    }

    @Scheduled(
            fixedDelayString = "${announcement.pdf-auto-extraction.check-interval}",
            initialDelayString = "${announcement.pdf-auto-extraction.initial-delay}"
    )
    public void enqueueDue() {
        long room = maxInFlight - jobs.countByKindAndRequestSourceAndStatusIn(
                PdfExtractionJob.KIND, PdfExtractionJob.BATCH, ACTIVE);
        if (room <= 0) {
            return;
        }
        List<Long> candidates = announcements.findOpenNotExtracted(LocalDate.ofInstant(clock.instant(), SEOUL),
                Set.copyOf(unavailable), CANDIDATE_LIMIT);
        Map<Long, List<AnnouncementAttachment>> byAnnouncement = attachments.findAllByAnnouncementIdIn(candidates)
                .stream()
                .collect(Collectors.groupingBy(attachment -> attachment.getAnnouncement().getId()));
        long started = 0;
        for (Long announcementId : candidates) {
            if (started >= room) {
                return;
            }
            Optional<AnnouncementAttachment> pdf = preferredPdf(byAnnouncement.getOrDefault(announcementId, List.of()));
            if (pdf.isPresent() && enqueue(announcementId, pdf.get())) {
                started++;
            }
        }
    }

    /** PDF 보정 화면(extractionApi.ts preferredPdf)과 같이 공고문 종류, 이름에 공고문·모집공고가 든 첨부, 첫 PDF 순서로 고른다. */
    static Optional<AnnouncementAttachment> preferredPdf(List<AnnouncementAttachment> attachments) {
        List<AnnouncementAttachment> pdfs = attachments.stream()
                .filter(attachment -> PDF.matcher(attachment.getFileName()).find())
                .toList();
        return pdfs.stream().filter(attachment -> attachment.getFileType() == AttachmentType.ANNOUNCEMENT).findFirst()
                .or(() -> pdfs.stream().filter(attachment -> NOTICE_NAME.matcher(attachment.getFileName()).find())
                        .findFirst())
                .or(() -> pdfs.stream().findFirst());
    }

    private boolean enqueue(long announcementId, AnnouncementAttachment attachment) {
        try {
            extractions.requestBatch(announcementId, attachment.getId());
            return true;
        }
        catch (PdfExtractionInProgressException exception) {
            return false;
        }
        catch (AttachmentUnavailableException exception) {
            if (!RETRYABLE.contains(exception.reason())) {
                unavailable.add(announcementId);
            }
            log.warn("event=announcement.pdf_extraction.batch_skipped announcementId={} attachmentId={} reason={}",
                    announcementId, attachment.getId(), exception.reason());
            return false;
        }
        catch (InvalidAnnouncementPdfException exception) {
            unavailable.add(announcementId);
            log.warn("event=announcement.pdf_extraction.batch_skipped announcementId={} attachmentId={} reason=INVALID_PDF",
                    announcementId, attachment.getId());
            return false;
        }
    }
}
