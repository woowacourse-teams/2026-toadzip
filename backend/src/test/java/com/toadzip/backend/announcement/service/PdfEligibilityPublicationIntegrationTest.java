package com.toadzip.backend.announcement.service;

import static com.toadzip.backend.announcement.service.PdfPublicationFixture.file;
import static com.toadzip.backend.announcement.service.PdfPublicationFixture.pdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.PdfExtractionInput.PublicationMode;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.external.AnnouncementAttachmentClient;
import com.toadzip.backend.announcement.service.PdfPublicationFixture.Candidate;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.DraftFromExtraction;
import com.toadzip.backend.eligibility.service.EligibilityExtractionDraftService;
import com.toadzip.backend.global.config.PostgresTestDatabases;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
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
@Import(PdfPublicationFixture.class)
class PdfEligibilityPublicationIntegrationTest {

    @Autowired private PdfPublicationScheduler publication;
    @Autowired private PdfPublicationService service;
    @Autowired private PdfPublicationFixture fixture;
    @Autowired private EligibilityExtractionDraftService eligibilityDrafts;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private AnnouncementAttachmentClient client;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.sql("TRUNCATE ai_jobs, announcements CASCADE").update();
        byte[] pdf = pdf(1);
        doAnswer(invocation -> file(pdf)).when(client).read(anyString());
    }

    @Test
    void 신청자격까지_확인된_공고는_공개한_뒤_신청자격도_공개한다() {
        Candidate candidate = fixture.candidate();
        Announcement notice = candidate.notice();
        long job = succeededJob(candidate);

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
        Candidate candidate = fixture.candidate();
        Announcement notice = candidate.notice();
        long job = succeededJob(candidate);
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
        Candidate candidate = fixture.candidate();
        Announcement notice = candidate.notice();
        long job = succeededJob(candidate);
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
        Candidate candidate = fixture.candidate();
        Announcement notice = candidate.notice();
        ObjectNode tree = verifiedExtraction(candidate);
        ObjectNode sheet = tree.withObject("data").withObject("supplySheet");
        sheet.withArray("targets").addObject().put("key", "t1").put("label", "신혼부부").putArray("groups")
                .add("NEWLYWED");
        Map<String, String> cell = Map.of("housingType", "h0", "target", "t1");
        sheet.withArray("cells").add(json.valueToTree(cell));
        ((ObjectNode) sheet.withArray("quotas").get(0)).withArray("members").add(json.valueToTree(cell));
        long job = fixture.succeededJob(candidate, tree);

        publication.process();

        assertThat(decisionOf(job)).containsEntry("outcome", "PUBLISHED")
                .containsEntry("eligibility_status", "REVIEW")
                .containsEntry("reason", "공고 보정 완료 · 신청자격 경로 또는 원문 연결 확인이 필요합니다.");
        assertThat(ruleSets(notice)).isEmpty();
    }

    @Test
    void 신청자격_초안을_만들지_못한_내부_사유는_검수함에_보이지_않는다() {
        Candidate candidate = fixture.candidate();
        Announcement notice = candidate.notice();
        ObjectNode tree = verifiedExtraction(candidate);
        // 공급대상과 이어지는 신청자격 경로가 하나도 없으면 초안 서비스가 관리자 화면용 예외로 거절한다.
        ObjectNode target = (ObjectNode) tree.withObject("data").withObject("eligibility").withArray("targets").get(0);
        target.put("target", "고령자").put("supplyTarget", "고령자").put("summary", "고령자");
        target.putArray("supplyTargets").add("고령자");
        target.putArray("supplyTargetKeys").add("t9");
        target.putArray("groups").add("ELDERLY");
        long job = fixture.succeededJob(candidate, tree);

        publication.process();

        assertThat(decisionOf(job)).containsEntry("outcome", "PUBLISHED")
                .containsEntry("eligibility_status", "REVIEW")
                .containsEntry("reason", "공고 보정 완료 · 신청자격 조건과 대상 연결을 확인하세요.");
        assertThat(ruleSets(notice)).isEmpty();
    }

    /** 방금 끝난 서버 추출 작업. 워커가 원문 대조를 모두 통과시켰고 신청자격도 확인했다. */
    private long succeededJob(Candidate candidate) {
        return fixture.succeededJob(candidate, verifiedExtraction(candidate));
    }

    /** 통과한 추출 결과의 공급대상을 '청년'으로 바꾸고 워커가 확인한 신청자격(공통 무주택, 청년 경로)을 더한다. */
    private ObjectNode verifiedExtraction(Candidate candidate) {
        ObjectNode tree = fixture.passedExtraction(candidate);
        ObjectNode data = tree.withObject("data");
        data.withObject("announcement").put("name", "자동 공개 공고");
        ObjectNode target = (ObjectNode) data.withObject("supplySheet").withArray("targets").get(0);
        target.put("label", "청년").putArray("groups").add("YOUTH");
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
        tree.withObject("publicationReview").put("eligibilityVerified", true);
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

    static class SchemaInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("pdfPublicationTestDatabase",
                    Map.of("spring.datasource.url", PostgresTestDatabases.migratedPrimaryUrl("pdfpublication_"))));
        }
    }
}
