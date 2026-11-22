package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.TemporaryAttachment;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 자동 공개 통합 테스트가 함께 쓰는 준비물이다. 공급표를 연결할 단지·주택형이 있는 공고와 공고문 PDF 첨부, 워커가 통과시킨 추출 결과,
 * 그 결과로 끝난 서버 추출 작업을 만든다.
 */
@TestComponent
class PdfPublicationFixture {
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    private final HousingComplexRepository complexes;
    private final HousingTypeRepository types;
    private final AnnouncementRepository announcements;
    private final AnnouncementAttachmentRepository attachments;
    private final AnnouncementPdfExtractionService extractions;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    PdfPublicationFixture(HousingComplexRepository complexes, HousingTypeRepository types,
            AnnouncementRepository announcements, AnnouncementAttachmentRepository attachments,
            AnnouncementPdfExtractionService extractions, JdbcClient jdbc, ObjectMapper json) {
        this.complexes = complexes;
        this.types = types;
        this.announcements = announcements;
        this.attachments = attachments;
        this.extractions = extractions;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 공고, 그 공고문 PDF 첨부, 공급표 주택형이 가리킬 단지 이름. */
    record Candidate(Announcement notice, AnnouncementAttachment pdf, String complexName) { }

    /**
     * 공급표를 연결할 단지·주택형이 있고 공고문 PDF 첨부가 붙은 미확정 LH 공고. 자동 공개는 LH 공고만 공고 단지를 주소·이름으로 잇는다
     * (SH·GH는 관리자가 확정한다).
     */
    Candidate candidate() {
        return candidate(Announcement.createUnconfirmed("LH:" + UUID.randomUUID(), "자동 공개 공고", AgencyCode.LH, TODAY,
                "https://example.com/notice"));
    }

    /** 저장하지 않은 공고에 공급표를 연결할 단지·주택형과 공고문 PDF 첨부를 붙인다. 공고의 기관은 LH여야 이름으로 단지를 찾는다. */
    Candidate candidate(Announcement unsaved) {
        String complexName = "검증단지-" + UUID.randomUUID();
        Address address = Address.create("서울 중구 세종대로", "1111010100100010000",
                "1111010100", "11", "11110", new BigDecimal("37.5"), new BigDecimal("126.9"));
        HousingComplex complex = complexes.saveAndFlush(HousingComplex.create(complexName,
                UUID.randomUUID().toString(), "HAPPY_HOUSING", address, 10, "LH", TODAY, "INDIVIDUAL", "APARTMENT",
                "STAIR", true, 10, null, 0));
        types.saveAndFlush(HousingType.createFromAnnouncement(complex, UUID.randomUUID().toString(),
                "36A", new BigDecimal("36"), null, null));
        Announcement notice = announcements.saveAndFlush(unsaved);
        return new Candidate(notice, attach(notice, "모집공고문.pdf", AttachmentType.ANNOUNCEMENT, 0), complexName);
    }

    AnnouncementAttachment attach(Announcement announcement, String fileName, AttachmentType type, int order) {
        return attachments.saveAndFlush(AnnouncementAttachment.create(announcement, fileName, type,
                "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=" + UUID.randomUUID(), order));
    }

    /**
     * 워커가 원문 대조를 모두 통과시킨 추출 결과. 공급표는 주택형 36A 하나를 공급대상 '전체'로 5세대 모집하고, 신청자격은 확인되지
     * 않았다.
     */
    ObjectNode passedExtraction(Candidate candidate) {
        ObjectNode tree = json.createObjectNode();
        ObjectNode data = tree.putObject("data");
        data.putObject("announcement").put("postedDate", TODAY.toString())
                .put("applicationStartDate", TODAY.toString())
                .put("applicationEndDate", TODAY.plusDays(2).toString())
                .put("winnerAnnouncementDate", TODAY.plusDays(5).toString());
        data.putArray("schedules");
        data.putArray("receptionPlaces");
        ObjectNode sheet = data.putObject("supplySheet");
        sheet.putArray("housingTypes").addObject().put("key", "h0").put("name", "36A")
                .put("complexName", candidate.complexName()).put("exclusiveArea", 36);
        sheet.putArray("targets").addObject().put("key", "t0").put("label", "전체").putArray("groups");
        Map<String, String> cell = Map.of("housingType", "h0", "target", "t0");
        sheet.putArray("cells").add(json.valueToTree(cell));
        sheet.putArray("quotas").addObject().put("count", 5).put("kind", "WINNER").putArray("members")
                .add(json.valueToTree(cell));
        sheet.putArray("leaseTerms");
        ObjectNode review = tree.putObject("publicationReview").put("policy", 1)
                .put("rentalType", "HAPPY_HOUSING").put("recruitmentType", "NEW").put("eligibilityPublishable", false);
        review.putArray("reasons");
        review.putArray("unconfirmedRequirements");
        review.putObject("evidence");
        review.putObject("answers");
        return tree;
    }

    /** 공고문 첨부로 넣은 서버 추출 작업이 이 결과로 방금 끝난 상태. */
    long succeededJob(Candidate candidate, ObjectNode tree) {
        long job = extractions.requestBatch(candidate.notice().getId(), candidate.pdf().getId());
        String result = json.writeValueAsString(Map.of("json", tree.toString(),
                "pages", List.of(1), "totalPages", 1, "warnings", List.of(),
                "usage", Map.of("promptTokens", 1, "outputTokens", 1, "thinkingTokens", 0)));
        jdbc.sql("""
                UPDATE ai_jobs SET status='SUCCEEDED', completed_at=now(), pdf=NULL, result=cast(? as jsonb) WHERE id=?
                """).params(result, job).update();
        return job;
    }

    static TemporaryAttachment file(byte[] bytes) throws IOException {
        TemporaryAttachment file = new TemporaryAttachment(() -> { });
        file.append(ByteBuffer.wrap(bytes));
        return file;
    }

    /** 쪽 수가 다르면 내용이 다른 PDF다. */
    static byte[] pdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page < pages; page++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}
