package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.PdfExtractionInput.PublicationMode;
import com.toadzip.backend.announcement.domain.TemporaryAttachment;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.external.AnnouncementAttachmentClient;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.DraftFromExtraction;
import com.toadzip.backend.eligibility.service.EligibilityExtractionDraftService;
import com.toadzip.backend.global.config.PostgresTestDatabases;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 자동 공개한 공고의 신청자격 자동 공개. 신청자격 저장·공개가 JDBC 전용 테이블과 함수를 쓰므로 실제 Flyway 스키마에서 확인한다.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "announcement.pdf-auto-extraction.enabled=true",
        "announcement.pdf-publication.mode=APPLY",
        "announcement.pdf-auto-extraction.initial-delay=PT24H"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ContextConfiguration(initializers = PdfEligibilityPublicationIntegrationTest.SchemaInitializer.class)
class PdfEligibilityPublicationIntegrationTest {
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired private PdfPublicationScheduler publication;
    @Autowired private PdfPublicationService service;
    @Autowired private AnnouncementPdfExtractionService extractions;
    @Autowired private EligibilityExtractionDraftService eligibilityDrafts;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private HousingTypeRepository types;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private AnnouncementAttachmentRepository attachments;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private AnnouncementAttachmentClient client;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.sql("TRUNCATE ai_jobs, announcements CASCADE").update();
        byte[] pdf = pdf();
        doAnswer(invocation -> file(pdf)).when(client).read(anyString());
    }

    @Test
    void 신청자격까지_확인된_공고는_공개한_뒤_신청자격도_공개한다() {
        Announcement notice = candidate();
        long job = succeededJob(notice);

        publication.process();

        assertThat(decisionOf(job)).containsEntry("outcome", "PUBLISHED")
                .containsEntry("eligibility_status", "PUBLISHED").containsEntry("reason", "공고와 신청자격 공개 완료");
        assertThat(announcements.findById(notice.getId()).orElseThrow().isPublished()).isTrue();
        assertThat(ruleSets(notice)).singleElement().satisfies(ruleSet -> {
            assertThat(ruleSet.get("status")).isEqualTo("PUBLISHED");
            assertThat(ruleSet.get("version")).isEqualTo(1L);
            assertThat(json.readTree(ruleSet.get("content").toString()).path("paths").get(0).path("label").asString())
                    .isEqualTo("청년");
        });
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 운영자가_먼저_공개한_신청자격이_있으면_자동으로_바꾸지_않고_검수로_남긴다() {
        Announcement notice = candidate();
        long job = succeededJob(notice);
        service.process(job, PublicationMode.APPLY);
        String sha256 = jdbc.sql("SELECT pdf_sha256 FROM ai_jobs WHERE id = ?").param(job).query(String.class).single();
        eligibilityDrafts.create(notice.getId(), new DraftFromExtraction(sha256, null, 0L));
        List<Map<String, Object>> before = ruleSets(notice);

        publication.process();

        assertThat(decisionOf(job)).containsEntry("outcome", "PUBLISHED")
                .containsEntry("eligibility_status", "REVIEW")
                .containsEntry("reason", "공고 보정 완료 · 기존 신청자격 검토본이 있습니다.");
        assertThat(before).singleElement()
                .satisfies(ruleSet -> assertThat(ruleSet.get("status")).isEqualTo("PUBLISHED"));
        assertThat(ruleSets(notice)).isEqualTo(before);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 공개한_뒤_공고가_바뀌면_신청자격을_공개하지_않고_검수로_남긴다() {
        Announcement notice = candidate();
        long job = succeededJob(notice);
        service.process(job, PublicationMode.APPLY);
        jdbc.sql("UPDATE announcements SET version = version + 1 WHERE id = ?").param(notice.getId()).update();

        publication.process();

        assertThat(decisionOf(job)).containsEntry("outcome", "PUBLISHED")
                .containsEntry("eligibility_status", "REVIEW")
                .containsEntry("reason", "공고 보정 완료 · 공고 또는 추출이 변경되었습니다.");
        assertThat(ruleSets(notice)).isEmpty();
    }

    @Test
    void 신청자격_경로가_없는_공급대상이_있으면_신청자격을_되돌리고_그_사유로_검수에_남긴다() {
        Announcement notice = candidate();
        ObjectNode tree = extraction(notice.getName());
        ObjectNode sheet = tree.withObject("data").withObject("supplySheet");
        sheet.withArray("targets").addObject().put("key", "t1").put("label", "신혼부부").putArray("groups")
                .add("NEWLYWED");
        Map<String, String> cell = Map.of("housingType", "h0", "target", "t1");
        sheet.withArray("cells").add(json.valueToTree(cell));
        ((ObjectNode) sheet.withArray("quotas").get(0)).withArray("members").add(json.valueToTree(cell));
        long job = succeededJob(notice, tree);

        publication.process();

        assertThat(decisionOf(job)).containsEntry("outcome", "PUBLISHED")
                .containsEntry("eligibility_status", "REVIEW")
                .containsEntry("reason", "공고 보정 완료 · 신청자격 경로 또는 원문 연결 확인이 필요합니다.");
        assertThat(ruleSets(notice)).isEmpty();
    }

    @Test
    void 신청자격_초안을_만들지_못한_내부_사유는_검수함에_보이지_않는다() {
        Announcement notice = candidate();
        ObjectNode tree = extraction(notice.getName());
        // 공급대상과 이어지는 신청자격 경로가 하나도 없으면 초안 서비스가 관리자 화면용 예외로 거절한다.
        ObjectNode target = (ObjectNode) tree.withObject("data").withObject("eligibility").withArray("targets").get(0);
        target.put("target", "고령자").put("supplyTarget", "고령자").put("summary", "고령자");
        target.putArray("supplyTargets").add("고령자");
        target.putArray("supplyTargetKeys").add("t9");
        target.putArray("groups").add("ELDERLY");
        long job = succeededJob(notice, tree);

        publication.process();

        assertThat(decisionOf(job)).containsEntry("outcome", "PUBLISHED")
                .containsEntry("eligibility_status", "REVIEW")
                .containsEntry("reason", "공고 보정 완료 · 신청자격 조건과 대상 연결을 확인하세요.");
        assertThat(ruleSets(notice)).isEmpty();
    }

    /** 공급표를 연결할 단지·주택형이 있고 공고문 PDF 첨부가 붙은 미확정 공고. */
    private Announcement candidate() {
        Address address = Address.create("서울 중구 세종대로", "1111010100100010000",
                "1111010100", "11", "11110", new BigDecimal("37.5"), new BigDecimal("126.9"));
        HousingComplex complex = complexes.saveAndFlush(HousingComplex.create("검증단지-" + UUID.randomUUID(),
                UUID.randomUUID().toString(), "HAPPY_HOUSING", address, 10, "SH", TODAY, "INDIVIDUAL", "APARTMENT",
                "STAIR", true, 10, null, 0));
        types.saveAndFlush(HousingType.createFromAnnouncement(complex, UUID.randomUUID().toString(),
                "36A", new BigDecimal("36"), null, null));
        Announcement notice = announcements.saveAndFlush(Announcement.createUnconfirmed("SH:" + UUID.randomUUID(),
                complex.getName(), AgencyCode.SH, TODAY, "https://example.com/notice"));
        attachments.saveAndFlush(AnnouncementAttachment.create(notice, "모집공고문.pdf", AttachmentType.ANNOUNCEMENT,
                "https://example.com/notice.pdf", 0));
        return notice;
    }

    /**
     * 공고문 첨부로 넣은 서버 추출 작업이 방금 끝난 상태. 워커가 원문 대조를 모두 통과시켰고 신청자격(공통 무주택, 청년 경로)도 확인했다.
     * 공고 이름이 곧 공급표 주택형의 단지명이다.
     */
    private long succeededJob(Announcement notice) {
        return succeededJob(notice, extraction(notice.getName()));
    }

    private long succeededJob(Announcement notice, ObjectNode tree) {
        long attachment = attachments.findAllByAnnouncementIdIn(List.of(notice.getId())).getFirst().getId();
        long job = extractions.requestBatch(notice.getId(), attachment);
        String result = json.writeValueAsString(Map.of("json", tree.toString(),
                "pages", List.of(1), "totalPages", 1, "warnings", List.of(),
                "usage", Map.of("promptTokens", 1, "outputTokens", 1, "thinkingTokens", 0)));
        jdbc.sql("""
                UPDATE ai_jobs SET status='SUCCEEDED', completed_at=now(), pdf=NULL, result=cast(? as jsonb) WHERE id=?
                """).params(result, job).update();
        return job;
    }

    private ObjectNode extraction(String complexName) {
        ObjectNode tree = json.createObjectNode();
        ObjectNode data = tree.putObject("data");
        data.putObject("announcement").put("name", "자동 공개 공고").put("postedDate", TODAY.toString())
                .put("applicationStartDate", TODAY.toString())
                .put("applicationEndDate", TODAY.plusDays(2).toString())
                .put("winnerAnnouncementDate", TODAY.plusDays(5).toString());
        data.putArray("schedules");
        data.putArray("receptionPlaces");
        Map<String, String> cell = Map.of("housingType", "h0", "target", "t0");
        ObjectNode sheet = data.putObject("supplySheet");
        sheet.putArray("housingTypes").addObject().put("key", "h0").put("name", "36A")
                .put("complexName", complexName).put("exclusiveArea", 36);
        sheet.putArray("targets").addObject().put("key", "t0").put("label", "청년").putArray("groups").add("YOUTH");
        sheet.putArray("cells").add(json.valueToTree(cell));
        sheet.putArray("quotas").addObject().put("count", 5).put("kind", "WINNER").putArray("members")
                .add(json.valueToTree(cell));
        sheet.putArray("leaseTerms");
        data.set("eligibility", json.valueToTree(Map.of(
                "common", Map.of("requirements", List.of(Map.of("kind", "NO_HOUSE", "scope", "HOUSEHOLD",
                        "asWritten", "무주택세대구성원")), "qualifications", List.of(), "summary", "무주택세대구성원"),
                "targets", List.of(Map.of("target", "청년", "supplyTarget", "청년", "supplyTargets", List.of("청년"),
                        "supplyTargetKeys", List.of("t0"), "groups", List.of("YOUTH"),
                        "requirements", List.of(Map.of("kind", "OTHER", "asWritten", "입주 전까지 청약통장 가입")),
                        "qualifications", List.of(), "summary", "청년")),
                "exclusions", List.of())));
        tree.putArray("evidence").addObject().put("path", "eligibility.common.requirements[0]").put("page", 1)
                .put("quote", "무주택세대구성원");
        ObjectNode review = tree.putObject("publicationReview").put("policy", 1)
                .put("rentalType", "HAPPY_HOUSING").put("recruitmentType", "NEW").put("eligibilityVerified", true);
        review.putArray("reasons");
        review.putObject("evidence");
        review.putObject("answers");
        return tree;
    }

    private Map<String, Object> decisionOf(long job) {
        return jdbc.sql("SELECT outcome, reason, eligibility_status FROM pdf_publication_decisions WHERE job_id=?")
                .param(job).query().singleRow();
    }

    private List<Map<String, Object>> ruleSets(Announcement notice) {
        return jdbc.sql("""
                SELECT id, status, revision, version, content::text AS content, updated_at, published_at
                FROM basic_comparison_rule_sets WHERE announcement_id = ? ORDER BY id
                """).param(notice.getId()).query().listOfRows();
    }

    private static TemporaryAttachment file(byte[] bytes) throws IOException {
        TemporaryAttachment file = new TemporaryAttachment(() -> { });
        file.append(ByteBuffer.wrap(bytes));
        return file;
    }

    private static byte[] pdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    static class SchemaInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("pdfPublicationTestDatabase",
                    Map.of("spring.datasource.url", PostgresTestDatabases.migratedPrimaryUrl("pdfpublication_"))));
        }
    }
}
