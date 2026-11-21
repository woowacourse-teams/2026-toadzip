package com.toadzip.backend.announcement.service;

import static com.toadzip.backend.announcement.service.PublicationCorrection.require;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.PdfExtractionInput;
import com.toadzip.backend.announcement.domain.PdfExtractionInput.Publication;
import com.toadzip.backend.announcement.domain.PdfExtractionInput.PublicationMode;
import com.toadzip.backend.announcement.domain.PdfExtractionJob;
import com.toadzip.backend.announcement.domain.PdfExtractionSource;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.Apply;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.Change;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.Preview;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.Request;
import com.toadzip.backend.announcement.dto.response.AttachmentContent;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.PdfExtractionJobRepository;
import com.toadzip.backend.announcement.repository.PdfPublicationDecisions;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.DraftFromExtraction;
import com.toadzip.backend.eligibility.service.EligibilityExtractionDraftService;
import com.toadzip.backend.eligibility.service.EligibilityRuleSetService;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
class PdfPublicationService {
    /**
     * 잠깐의 실패(기관 서버 무응답, DB 잠금 대기·직렬화 실패)로 판정하지 못한 작업을 다음 주기로 미루는 기간(작업 완료 시각 기준).
     * 넘기면 검수로 넘긴다. 미룬 작업이 계속 앞자리를 차지하면 뒤의 작업을 판정하지 못한다.
     */
    private static final Duration RETRY_WINDOW = Duration.ofHours(1);
    /** 수집한 값이 '기타'면 아직 분류하지 못한 것으로 보는 기본정보 항목과 그 값. */
    private static final Map<String, String> UNCLASSIFIED_TYPES = Map.of(
            "rentalType", RentalType.ETC.name(), "recruitmentType", RecruitmentType.ETC.name());

    private final PdfPublicationDecisions decisions;
    private final PdfExtractionJobRepository jobs;
    private final AnnouncementRepository announcements;
    private final AnnouncementAttachmentRepository attachments;
    private final AnnouncementAttachmentService documents;
    private final PdfExtractionInputs inputs;
    private final AnnouncementJsonCorrectionService corrections;
    private final PublicationCorrection proposals;
    private final EligibilityRuleSetService rules;
    private final EligibilityExtractionDraftService eligibility;
    private final ObjectMapper json;
    private final Validator validator;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final IngestExecutionOwnershipService ownership;

    PdfPublicationService(PdfPublicationDecisions decisions, PdfExtractionJobRepository jobs,
            AnnouncementRepository announcements, AnnouncementAttachmentRepository attachments,
            AnnouncementAttachmentService documents, PdfExtractionInputs inputs,
            AnnouncementJsonCorrectionService corrections, PublicationCorrection proposals,
            EligibilityRuleSetService rules, EligibilityExtractionDraftService eligibility, ObjectMapper json,
            Validator validator, Clock clock, PlatformTransactionManager transactionManager,
            IngestExecutionOwnershipService ownership) {
        this.decisions = decisions;
        this.jobs = jobs;
        this.announcements = announcements;
        this.attachments = attachments;
        this.documents = documents;
        this.inputs = inputs;
        this.corrections = corrections;
        this.proposals = proposals;
        this.rules = rules;
        this.eligibility = eligibility;
        this.json = json;
        this.validator = validator;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        // 보정 서비스의 REPEATABLE_READ는 이미 열린 트랜잭션에 합류하면 적용되지 않으므로 판정 트랜잭션을 그 격리 수준으로 연다.
        // 판정 중에 다른 트랜잭션이 같은 행을 바꾸면 직렬화 실패가 나고, 그 작업은 다음 주기에 다시 판정한다.
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.ownership = ownership;
    }

    /**
     * 공고문을 기관 서버에서 다시 받는 동안에는 DB 트랜잭션, 공고 행 잠금, 수집 실행 소유권을 잡지 않는다. 공고를 읽고 쓰는 판정과
     * 적용만 소유권 안의 한 트랜잭션이다. 수집·정제가 돌고 있으면 판정하지 않고 IngestAlreadyRunningException을 던진다.
     */
    public void process(long jobId, PublicationMode mode) {
        PdfExtractionJob job = jobs.findById(jobId).orElseThrow();
        PdfExtractionInput input = json.readValue(job.getInput(), PdfExtractionInput.class);
        JsonNode tree = json.readTree(json.readTree(job.getResult()).required("json").asString());
        JsonNode review = tree.path("publicationReview");
        require(review.path("policy").asInt(0) == 1, "공개 판정이 없는 추출입니다.");
        List<String> reasons = PublicationCorrection.array(review, "reasons").stream().map(JsonNode::asString).toList();
        if (!reasons.isEmpty()) {
            decisions.record(jobId, "REVIEW", String.join("\n", reasons), null, null);
            return;
        }
        Optional<String> downloaded = downloadedSha256(job, input.publication().attachmentId());
        if (downloaded.isEmpty()) {
            return;
        }
        require(downloaded.get().equals(job.getPdfSha256()), "공고문 PDF 내용이 변경되었습니다.");
        try (var lease = ownership.acquire()) {
            transactions.executeWithoutResult(status -> decide(job, input, tree, mode));
        }
    }

    /** 지금 첨부의 SHA-256. 잠깐의 실패라 다음 주기에 다시 받을 작업이면 비어 있다. */
    private Optional<String> downloadedSha256(PdfExtractionJob job, long attachmentId) {
        try {
            AttachmentContent content = documents.readForAdmin(job.getAnnouncementId(), attachmentId);
            byte[] pdf = AnnouncementPdfExtractionService.bytesOf(content.file());
            return Optional.of(PdfExtractionSource.of(content.fileName(), pdf).sha256());
        } catch (AttachmentUnavailableException unavailable) {
            if (!PdfAutoExtractionScheduler.RETRYABLE.contains(unavailable.reason()) || !withinRetryWindow(job)) {
                throw new PublicationHold("공고문 PDF를 다시 받지 못했습니다. 첨부를 확인하세요.", unavailable);
            }
            log.warn("event=pdf.publication.download_deferred jobId={} attachmentId={} reason={}",
                    job.getId(), attachmentId, unavailable.reason());
            return Optional.empty();
        }
    }

    /**
     * 잠금 대기 초과·교착·직렬화 실패·낙관적 잠금 충돌처럼 다시 하면 풀리는 DB 실패로 판정하지 못한 작업인지. 이런 작업은 결정을
     * 남기지 않고 다음 주기에 다시 판정한다. 재시도 기간을 넘긴 작업은 해당하지 않는다.
     */
    public boolean retriesNextCycle(long jobId, RuntimeException failure) {
        boolean momentary = failure instanceof TransientDataAccessException
                || failure instanceof RecoverableDataAccessException
                || failure instanceof CannotCreateTransactionException;
        return momentary && withinRetryWindow(jobs.findById(jobId).orElseThrow());
    }

    private boolean withinRetryWindow(PdfExtractionJob job) {
        Duration sinceCompleted = Duration.between(job.getCompletedAt(), clock.instant());
        return sinceCompleted.compareTo(RETRY_WINDOW) <= 0;
    }

    private void decide(PdfExtractionJob job, PdfExtractionInput input, JsonNode tree, PublicationMode mode) {
        long jobId = job.getId();
        if (!decisions.claim(jobId)) {
            return;
        }
        long id = job.getAnnouncementId();
        Publication snapshot = input.publication();
        Announcement announcement = announcements.findByIdForUpdate(id).orElseThrow();
        require(decisions.isLatest(jobId, id) && announcement.getVersion() == snapshot.announcementVersion()
                && !announcement.isAdminModified() && !announcement.isAdminDeleted()
                && !announcement.isSourceReviewRequired() && !announcement.isSupplyConfirmed()
                && announcement.getStatus() == AnnouncementPublicationType.ORIGINAL, "공고·원천이 변경되었거나 이미 검토 중입니다.");
        require(input.sourceSupplyRows().equals(inputs.read(id).sourceSupplyRows()), "기관 공급 원천이 달라졌습니다.");
        List<AnnouncementAttachment> files = attachments.findAllByAnnouncementIdIn(List.of(id));
        require(files.stream().noneMatch(file -> file.getFileType() == AttachmentType.CORRECTION
                || file.getFileType() == AttachmentType.CANCELLATION), "정정·취소 첨부를 확인하세요.");
        // 트랜잭션 밖에서 받은 첨부가 지금도 이 공고의 공고문인지 본다.
        AnnouncementAttachment pdf = PdfAutoExtractionScheduler.preferredPdf(files).orElseThrow();
        require(pdf.getId() == snapshot.attachmentId() && pdf.getFileName().equals(job.getFileName()),
                "공고문 첨부가 변경되었습니다.");
        Request request = proposals.prepare(corrections.workspace(id), tree,
                job.getCompletedAt().atOffset(ZoneOffset.UTC), jobId);
        require(validator.validate(request.correction()).isEmpty(), "공급표의 값·참조 형식을 확인하세요.");
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        require(!request.document().announcement().applicationEndDate().isBefore(today), "접수가 마감되었습니다.");
        Preview preview = corrections.preview(id, request);
        boolean previouslyPublished = announcement.isPublished();
        require(preview.publishes() || previouslyPublished, "공고를 공개할 수 없습니다.");
        for (Change field : preview.fields()) {
            require(fillsEmpty(field), "기존 값과 PDF가 다릅니다: " + field.field());
        }
        if (mode != PublicationMode.APPLY || snapshot.mode() != mode) {
            decisions.record(jobId, outcome(previouslyPublished, "WOULD_CONFIRM", "WOULD_PUBLISH"),
                    "공개 기준 통과 · 모의 판정", null, null);
            return;
        }
        corrections.apply(id, new Apply(request, preview.confirmationToken()), PdfPublicationScheduler.ACTOR);
        String outcome = outcome(previouslyPublished, "CONFIRMED", "PUBLISHED");
        if (tree.path("publicationReview").path("eligibilityVerified").booleanValue(false)) {
            decisions.record(jobId, outcome, "공개 기준 통과", announcement.getVersion(), "PENDING");
            return;
        }
        decisions.record(jobId, outcome, "공고 보정 완료 · 신청자격의 원문 근거와 대상별 예외를 확인하세요.",
                announcement.getVersion(), "REVIEW");
    }

    /**
     * 자동 보정은 비어 있는 값만 채우고 이미 있는 값은 바꾸지 않는다. 수집한 유형 '기타'는 기관 분류에 맞는 유형이 없었다는 뜻이라
     * 빈 값처럼 워커가 원문으로 확인한 유형으로 바꾼다.
     */
    private static boolean fillsEmpty(Change field) {
        if (field.before() == null) {
            return true;
        }
        if (!(field.before() instanceof JsonNode before)) {
            return false;
        }
        if (before.isNull() || before.isMissingNode()) {
            return true;
        }
        if (before.isArray()) {
            return before.isEmpty();
        }
        return before.isString() && before.asString().equals(UNCLASSIFIED_TYPES.get(field.field()));
    }

    private static String outcome(boolean previouslyPublished, String confirmed, String published) {
        if (previouslyPublished) {
            return confirmed;
        }
        return published;
    }

    @Transactional
    public void publishEligibility(long jobId) {
        var version = decisions.claimEligibility(jobId);
        if (version.isEmpty()) { return; }
        var job = jobs.findById(jobId).orElseThrow();
        long id = job.getAnnouncementId();
        var notice = announcements.findByIdForUpdate(id).orElseThrow();
        require(notice.isPublished() && !notice.isAdminDeleted() && notice.getVersion() == version.get()
                && decisions.isLatest(jobId, id), "공고 또는 추출이 변경되었습니다.");
        require(rules.draft(id) == null && rules.published(id) == null, "기존 신청자격 검토본이 있습니다.");
        var result = eligibility.create(id, new DraftFromExtraction(job.getPdfSha256(), null, 0L));
        // 작성·공개·투영까지 같은 트랜잭션이다. 연결하지 못한 경로가 있으면 자격만 되돌린다.
        require(result.published() && result.notices().isEmpty(), "신청자격 경로 또는 원문 연결 확인이 필요합니다.");
        decisions.finishEligibility(jobId, "PUBLISHED", "공고와 신청자격 공개 완료");
    }
}
