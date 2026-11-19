package com.toadzip.backend.announcement.service;

import static com.toadzip.backend.announcement.service.PublicationCorrection.require;

import com.toadzip.backend.announcement.domain.*;
import com.toadzip.backend.announcement.dto.correction.AnnouncementJsonCorrection.Apply;
import com.toadzip.backend.announcement.repository.*;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.DraftFromExtraction;
import com.toadzip.backend.eligibility.service.EligibilityExtractionDraftService;
import com.toadzip.backend.eligibility.service.EligibilityRuleSetService;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
class PdfPublicationService {
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

    @Transactional
    public void process(long jobId, PdfExtractionInput.PublicationMode mode) {
        if (!decisions.claim(jobId)) { return; }
        var job = jobs.findById(jobId).orElseThrow();
        var input = json.readValue(job.getInput(), PdfExtractionInput.class);
        var snapshot = input.publication();
        var tree = json.readTree(json.readTree(job.getResult()).required("json").asString());
        var review = tree.path("publicationReview");
        require(review.path("policy").asInt(0) == 1, "공개 판정이 없는 추출입니다.");
        var reasons = PublicationCorrection.array(review, "reasons").stream().map(JsonNode::asString).toList();
        if (!reasons.isEmpty()) {
            decisions.record(jobId, "REVIEW", String.join("\n", reasons), null, null);
            return;
        }
        checkAnswers(review);
        long id = job.getAnnouncementId();
        var announcement = announcements.findByIdForUpdate(id).orElseThrow();
        require(decisions.isLatest(jobId, id) && announcement.getVersion() == snapshot.announcementVersion()
                && !announcement.isAdminModified() && !announcement.isAdminDeleted()
                && !announcement.isSourceReviewRequired() && !announcement.isSupplyConfirmed()
                && announcement.getStatus() == AnnouncementPublicationType.ORIGINAL, "공고·원천이 변경되었거나 이미 검토 중입니다.");
        require(input.sourceSupplyRows().equals(inputs.read(id).sourceSupplyRows()), "기관 공급 원천이 달라졌습니다.");
        var files = attachments.findAllByAnnouncementIdIn(List.of(id));
        require(files.stream().noneMatch(a -> a.getFileType() == AttachmentType.CORRECTION
                || a.getFileType() == AttachmentType.CANCELLATION), "정정·취소 첨부를 확인하세요.");
        var pdf = PdfAutoExtractionScheduler.preferredPdf(files).orElseThrow();
        require(pdf.getId() == snapshot.attachmentId() && pdf.getFileName().equals(job.getFileName()), "공고문 첨부가 변경되었습니다.");
        var content = documents.readForAdmin(id, pdf.getId());
        var fresh = PdfExtractionSource.of(content.fileName(), AnnouncementPdfExtractionService.bytesOf(content.file()));
        require(fresh.sha256().equals(job.getPdfSha256()), "공고문 PDF 내용이 변경되었습니다.");
        var request = proposals.prepare(corrections.workspace(id), tree, job.getCompletedAt().atOffset(ZoneOffset.UTC), jobId);
        require(validator.validate(request.correction()).isEmpty(), "공급표의 값·참조 형식을 확인하세요.");
        require(!request.document().announcement().applicationEndDate().isBefore(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))),
                "접수가 마감되었습니다.");
        var preview = corrections.preview(id, request);
        boolean previouslyPublished = announcement.isPublished();
        require(preview.publishes() || previouslyPublished, "공고를 공개할 수 없습니다.");
        for (var field : preview.fields()) {
            require(field.before() == null || field.before() instanceof JsonNode before && (before.isNull() || before.isMissingNode() || before.isArray() && before.isEmpty()),
                    "기존 값과 PDF가 다릅니다: " + field.field());
        }
        if (mode != PdfExtractionInput.PublicationMode.APPLY || snapshot.mode() != mode) {
            decisions.record(jobId, previouslyPublished ? "WOULD_CONFIRM" : "WOULD_PUBLISH", "공개 기준 통과 · 모의 판정", null, null);
            return;
        }
        corrections.apply(id, new Apply(request, preview.confirmationToken()), PdfPublicationScheduler.ACTOR);
        boolean verifiedEligibility = eligibilityReady(review);
        decisions.record(jobId, previouslyPublished ? "CONFIRMED" : "PUBLISHED",
                verifiedEligibility ? "공개 기준 통과" : "공고 보정 완료 · 신청자격의 원문 근거와 대상별 예외를 확인하세요.",
                announcement.getVersion(), verifiedEligibility ? "PENDING" : "REVIEW");
    }

    private static boolean eligibilityReady(JsonNode review) {
        var answer = review.path("answers").path("eligibility");
        double probability = answer.path("probability").asDouble(-1);
        return "confirmed".equals(answer.path("choice").asString("")) && probability >= 0.95 && probability <= 1;
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

    private void checkAnswers(JsonNode review) {
        var answers = review.path("answers");
        for (String field : List.of("applicationStartDate", "applicationEndDate", "winnerAnnouncementDate",
                "rentalType", "recruitmentType", "notice", "supply", "schedules")) {
            var answer = answers.path(field);
            double probability = answer.path("probability").asDouble(-1);
            require(probability >= 0.95 && probability <= 1, "JEV 확인 필요: " + field);
            if (!field.endsWith("Type")) {
                require("confirmed".equals(answer.path("choice").asString("")), "JEV 원문 불일치: " + field);
            }
        }
    }
}
