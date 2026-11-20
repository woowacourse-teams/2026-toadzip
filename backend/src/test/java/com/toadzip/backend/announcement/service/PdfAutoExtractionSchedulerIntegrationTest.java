package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.PdfExtractionInput.PublicationMode;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.TemporaryAttachment;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException.Reason;
import com.toadzip.backend.announcement.exception.PdfExtractionInProgressException;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementSearchRepository;
import com.toadzip.backend.announcement.repository.external.AnnouncementAttachmentClient;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {
        "announcement.pdf-auto-extraction.enabled=true",
        "announcement.pdf-publication.mode=APPLY",
        "app.notification.publisher-enabled=true", "app.notification.activation-at=2026-01-01T00:00:00Z",
        "announcement.pdf-auto-extraction.initial-delay=PT24H",
        "announcement.pdf-auto-extraction.max-in-flight=2"
})
@Sql(scripts = "/db/migration/V20261119_03__pdf_publication_decisions.sql",
        executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(statements = {"DROP TABLE pdf_publication_decisions", "DROP INDEX ix_ai_jobs_publication_pending"},
        executionPhase = ExecutionPhase.AFTER_TEST_CLASS)
@ActiveProfiles("test")
class PdfAutoExtractionSchedulerIntegrationTest {
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired private PdfPublicationScheduler publication;
    @Autowired private PdfPublicationService service;
    @Autowired private ObjectMapper json;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private HousingTypeRepository types;
    @Autowired private PdfAutoExtractionScheduler scheduler;
    @Autowired private AnnouncementPdfExtractionService extractions;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private AnnouncementSearchRepository search;
    @Autowired private AnnouncementAttachmentRepository attachments;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private AnnouncementAttachmentClient client;

    /** 첨부 서버가 돌려주는 PDF. 만들 때마다 문서 ID가 달라지므로 같은 PDF를 올리는 경우는 이 바이트를 쓴다. */
    private byte[] attachmentPdf;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.sql("TRUNCATE ai_jobs, announcements CASCADE").update();
        attachmentPdf = pdf();
        doAnswer(invocation -> file(attachmentPdf)).when(client).read(anyString());
    }

    @Test
    void 마감_전에_추출한_적_없는_공고만_공고문_PDF로_서버_작업을_넣는다() {
        Announcement open = announcement(TODAY.plusDays(5));
        attach(open, "신청서 양식.pdf", AttachmentType.ETC, 0);
        attach(open, "입주자모집 안내.pdf", AttachmentType.ANNOUNCEMENT, 1);
        attach(announcement(TODAY.minusDays(10)), "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
        Announcement confirmed = announcement(TODAY.plusDays(5));
        attach(confirmed, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
        jdbc.sql("UPDATE announcements SET supply_confirmed_at = now() WHERE id = :id")
                .param("id", confirmed.getId()).update();
        attach(announcement(TODAY.plusDays(5)), "공고문.hwp", AttachmentType.ANNOUNCEMENT, 0);

        scheduler.enqueueDue();

        assertThat(jobs()).singleElement().satisfies(job -> {
            assertThat(job.get("announcement_id")).isEqualTo(open.getId());
            assertThat(job.get("file_name")).isEqualTo("입주자모집 안내.pdf");
            assertThat(job.get("request_source")).isEqualTo("BATCH");
            assertThat(job.get("requested_by")).isNull();
            assertThat(job.get("status")).isEqualTo("PENDING");
        });
    }

    @Test
    void 진행_중인_서버_작업이_상한이면_더_넣지_않고_추출한_공고는_다시_넣지_않는다() {
        for (int index = 0; index < 3; index++) {
            attach(announcement(TODAY.plusDays(5)), "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
        }

        scheduler.enqueueDue();
        scheduler.enqueueDue();

        assertThat(jobs()).hasSize(2);
        jdbc.sql("UPDATE ai_jobs SET status = 'FAILED', pdf = NULL, error_code = 'X', completed_at = now()").update();

        scheduler.enqueueDue();

        assertThat(jobs()).hasSize(3);
    }

    @Test
    void 첨부가_PDF가_아니면_작업을_넣지_않고_다음_확인에서_다시_받지_않는다() {
        doAnswer(invocation -> file("<html>error</html>".getBytes(StandardCharsets.US_ASCII)))
                .when(client).read(anyString());
        attach(announcement(TODAY.plusDays(5)), "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);

        scheduler.enqueueDue();
        scheduler.enqueueDue();

        assertThat(jobs()).isEmpty();
        verify(client, times(1)).read(anyString());
    }

    @Test
    void 건너뛸_공고가_후보_수를_다_차지해도_그보다_오래된_공고를_후보로_고른다() {
        Announcement older = announcement(TODAY.plusDays(5));
        attach(older, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
        Announcement skipped = announcement(TODAY.plusDays(5));
        attach(skipped, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);

        assertThat(search.findOpenNotExtracted(TODAY, Set.of(skipped.getId()), 1)).containsExactly(older.getId());
        assertThat(search.findOpenNotExtracted(TODAY, Set.of(), 1)).containsExactly(skipped.getId());
    }

    @Test
    void 관리자가_같은_첨부를_요청하면_기다리는_서버_작업이_관리자_작업이_되어_먼저_처리된다() {
        Announcement open = announcement(TODAY.plusDays(5));
        AnnouncementAttachment notice = attach(open, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
        scheduler.enqueueDue();

        assertThatThrownBy(() -> extractions.requestAttachment(open.getId(), "admin", notice.getId()))
                .isInstanceOf(PdfExtractionInProgressException.class);

        assertThat(jobs()).singleElement().satisfies(job -> {
            assertThat(job.get("request_source")).isEqualTo("ADMIN");
            assertThat(job.get("requested_by")).isEqualTo("admin");
            assertThat(job.get("status")).isEqualTo("PENDING");
        });
    }

    @Test
    void 관리자가_같은_PDF를_직접_올리면_기다리는_서버_작업이_관리자_작업이_된다() {
        Announcement open = announcement(TODAY.plusDays(5));
        attach(open, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
        scheduler.enqueueDue();

        assertThatThrownBy(() -> extractions.request(open.getId(), "admin", "내려받은 공고문.pdf", attachmentPdf))
                .isInstanceOf(PdfExtractionInProgressException.class);

        assertThat(jobs()).singleElement().satisfies(job -> {
            assertThat(job.get("file_name")).isEqualTo("공고문.pdf");
            assertThat(job.get("request_source")).isEqualTo("ADMIN");
            assertThat(job.get("requested_by")).isEqualTo("admin");
        });
    }

    @Test
    void 관리자가_다른_PDF를_올리면_기다리는_서버_작업을_끝내고_관리자_작업을_바로_넣는다() throws IOException {
        Announcement open = announcement(TODAY.plusDays(5));
        attach(open, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
        scheduler.enqueueDue();
        byte[] correction = pdf(2);

        extractions.request(open.getId(), "admin", "공고문.pdf", correction);

        assertThat(jobs()).satisfiesExactly(
                batch -> {
                    assertThat(batch.get("request_source")).isEqualTo("BATCH");
                    assertThat(batch.get("status")).isEqualTo("FAILED");
                    assertThat(batch.get("error_code")).isEqualTo("REPLACED_BY_ADMIN");
                },
                admin -> {
                    assertThat(admin.get("request_source")).isEqualTo("ADMIN");
                    assertThat(admin.get("requested_by")).isEqualTo("admin");
                    assertThat(admin.get("status")).isEqualTo("PENDING");
                });
    }

    @Test
    void 관리자가_내용이_다른_첨부를_고르면_이름이_같아도_처리_중인_서버_작업을_끝내고_관리자_작업을_넣는다() throws IOException {
        Announcement open = announcement(TODAY.plusDays(5));
        attach(open, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
        AnnouncementAttachment correction = attach(open, "공고문.pdf", AttachmentType.ETC, 1);
        byte[] correctionBytes = pdf(3);
        doAnswer(invocation -> file(correctionBytes)).when(client).read(correction.getFileUrl());
        scheduler.enqueueDue();
        jdbc.sql("""
                UPDATE ai_jobs SET status = 'RUNNING', attempts = 1, lease_token = gen_random_uuid(),
                    lease_until = now() + interval '10 minutes', started_at = now()
                """).update();

        extractions.requestAttachment(open.getId(), "admin", correction.getId());

        assertThat(jobs()).satisfiesExactly(
                batch -> {
                    assertThat(batch.get("status")).isEqualTo("FAILED");
                    assertThat(batch.get("error_code")).isEqualTo("REPLACED_BY_ADMIN");
                    assertThat(batch.get("lease_token")).isNull();
                },
                admin -> {
                    assertThat(admin.get("request_source")).isEqualTo("ADMIN");
                    assertThat(admin.get("status")).isEqualTo("PENDING");
                });
    }

    @Test
    void 진행_중인_관리자_작업은_다른_관리자의_다른_PDF_요청이_끝내지_않는다() throws IOException {
        Announcement open = announcement(TODAY.plusDays(5));
        byte[] first = pdf(2);
        extractions.request(open.getId(), "admin-1", "공고문.pdf", first);
        byte[] second = pdf(3);

        assertThatThrownBy(() -> extractions.request(open.getId(), "admin-2", "정정 공고문.pdf", second))
                .isInstanceOf(PdfExtractionInProgressException.class);

        assertThat(jobs()).singleElement().satisfies(job -> {
            assertThat(job.get("requested_by")).isEqualTo("admin-1");
            assertThat(job.get("status")).isEqualTo("PENDING");
        });
    }

    private List<Map<String, Object>> jobs() {
        return jdbc.sql("""
                SELECT announcement_id, file_name, request_source, requested_by, status, error_code, lease_token
                FROM ai_jobs ORDER BY id
                """)
                .query().listOfRows();
    }

    @ParameterizedTest
    @ValueSource(strings = {"apply", "shadow", "changedPdf", "conflict", "published"})
    void 자동_공개와_보류는_원문과_같은_판정에만_한번_적용된다(String scenario) throws IOException {
        Candidate candidate = candidate();
        Announcement notice = candidate.notice();
        if (scenario.equals("published")) {
            jdbc.sql("UPDATE announcements SET release_status='PUBLISHED' WHERE id=?").param(notice.getId()).update();
        }
        ObjectNode tree = passedExtraction(candidate);
        if (scenario.equals("conflict")) {
            tree.withObject("publicationReview").withArray("reasons").add("7쪽 일정 예외");
        }
        long job = succeededJob(candidate, tree);
        if (scenario.equals("changedPdf")) {
            attachmentPdf = pdf(2);
        }
        if (scenario.equals("shadow")) {
            jdbc.sql("UPDATE ai_jobs SET input=jsonb_set(input, '{publication,mode}', '\"SHADOW\"') WHERE id=?").param(job).update();
        }

        publication.process();
        publication.process();

        var result = jdbc.sql("SELECT outcome, reason FROM pdf_publication_decisions WHERE job_id=?").param(job).query().singleRow();
        assertThat(result.get("outcome")).as("%s", result.get("reason")).isEqualTo(switch (scenario) {
            case "apply" -> "PUBLISHED";
            case "published" -> "CONFIRMED";
            case "shadow" -> "WOULD_PUBLISH";
            default -> "REVIEW";
        });
        assertThat(published(notice)).isEqualTo(scenario.equals("apply") || scenario.equals("published"));
        assertThat(jdbc.sql("SELECT count(*) FROM notification_outbox_events WHERE announcement_id=?")
                .param(notice.getId()).query(Integer.class).single()).isEqualTo(scenario.equals("apply") ? 1 : 0);
    }

    @Test
    void 기관_서버가_잠깐_응답하지_않으면_판정을_미루고_다음_실행에서_공개한다() {
        Candidate candidate = candidate();
        long job = succeededJob(candidate, passedExtraction(candidate));
        doThrow(new AttachmentUnavailableException(Reason.UPSTREAM_FAILURE)).when(client).read(anyString());

        publication.process();

        assertThat(decisionsOf(job)).isEmpty();
        assertThat(published(candidate.notice())).isFalse();

        doAnswer(invocation -> file(attachmentPdf)).when(client).read(anyString());
        publication.process();

        assertThat(decisionsOf(job)).singleElement()
                .satisfies(decision -> assertThat(decision.get("outcome")).isEqualTo("PUBLISHED"));
        assertThat(published(candidate.notice())).isTrue();
    }

    @Test
    void 다운로드_실패가_작업_완료_후_한_시간을_넘기면_읽을_수_있는_사유로_검수에_넘긴다() {
        Candidate candidate = candidate();
        long job = succeededJob(candidate, passedExtraction(candidate));
        jdbc.sql("UPDATE ai_jobs SET completed_at = now() - interval '2 hours' WHERE id=?").param(job).update();
        doThrow(new AttachmentUnavailableException(Reason.UPSTREAM_FAILURE)).when(client).read(anyString());

        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision -> {
            assertThat(decision.get("outcome")).isEqualTo("REVIEW");
            assertThat(decision.get("reason")).isEqualTo("공고문 PDF를 다시 받지 못했습니다. 첨부를 확인하세요.");
        });
        assertThat(published(candidate.notice())).isFalse();
    }

    @Test
    void 다시_받은_첨부가_PDF가_아니면_기다리지_않고_읽을_수_있는_사유로_검수에_넘긴다() {
        Candidate candidate = candidate();
        long job = succeededJob(candidate, passedExtraction(candidate));
        doAnswer(invocation -> file("<html>error</html>".getBytes(StandardCharsets.US_ASCII)))
                .when(client).read(anyString());

        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision -> {
            assertThat(decision.get("outcome")).isEqualTo("REVIEW");
            assertThat(decision.get("reason")).isEqualTo("공고문 PDF를 다시 받지 못했습니다. 첨부를 확인하세요.");
        });
        assertThat(published(candidate.notice())).isFalse();
    }

    @Test
    void 같은_공고에_더_새로운_추출_작업이_있으면_옛_작업으로_공개하지_않는다() throws IOException {
        Candidate candidate = candidate();
        long job = succeededJob(candidate, passedExtraction(candidate));
        extractions.request(candidate.notice().getId(), "admin", "정정 공고문.pdf", pdf(2));

        publication.process();

        assertThat(decisionsOf(job)).singleElement()
                .satisfies(decision -> assertThat(decision.get("outcome")).isEqualTo("REVIEW"));
        assertThat(published(candidate.notice())).isFalse();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 신청자격이_확인되지_않으면_공고만_공개하고_신청자격은_검수로_남긴다() {
        Candidate candidate = candidate();
        long job = succeededJob(candidate, passedExtraction(candidate));

        service.process(job, PublicationMode.APPLY);

        assertThat(published(candidate.notice())).isTrue();
        assertThat(decisionsOf(job)).singleElement()
                .satisfies(decision -> assertThat(decision.get("eligibility_status")).isEqualTo("REVIEW"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 신청자격까지_확인된_공고는_공개하고_신청자격_자동_공개를_기다린다() {
        Candidate candidate = candidate();
        ObjectNode tree = passedExtraction(candidate);
        tree.withObject("publicationReview").put("eligibilityVerified", true);
        long job = succeededJob(candidate, tree);

        service.process(job, PublicationMode.APPLY);

        assertThat(published(candidate.notice())).isTrue();
        assertThat(decisionsOf(job)).singleElement()
                .satisfies(decision -> assertThat(decision.get("eligibility_status")).isEqualTo("PENDING"));
    }

    @Test
    void 공고문을_다시_받는_동안에는_DB_트랜잭션을_잡지_않는다() {
        Candidate candidate = candidate();
        long job = succeededJob(candidate, passedExtraction(candidate));
        List<Boolean> transactionActive = new ArrayList<>();
        doAnswer(invocation -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            return file(attachmentPdf);
        }).when(client).read(anyString());

        publication.process();

        assertThat(transactionActive).containsExactly(false);
        assertThat(decisionsOf(job)).singleElement()
                .satisfies(decision -> assertThat(decision.get("outcome")).isEqualTo("PUBLISHED"));
    }

    /** 공급표를 연결할 단지·주택형이 있고 공고문 PDF 첨부가 붙은 미확정 공고. */
    private Candidate candidate() {
        String complexName = "검증단지-" + UUID.randomUUID();
        Address address = Address.create("서울 중구 세종대로", "1111010100100010000",
                "1111010100", "11", "11110", new BigDecimal("37.5"), new BigDecimal("126.9"));
        HousingComplex complex = complexes.saveAndFlush(HousingComplex.create(complexName,
                UUID.randomUUID().toString(), "HAPPY_HOUSING", address, 10, "SH", TODAY, "INDIVIDUAL", "APARTMENT",
                "STAIR", true, 10, null, 0));
        types.saveAndFlush(HousingType.createFromAnnouncement(complex, UUID.randomUUID().toString(),
                "36A", new BigDecimal("36"), null, null));
        Announcement notice = announcements.saveAndFlush(Announcement.createUnconfirmed("SH:" + UUID.randomUUID(),
                "자동 공개 공고", AgencyCode.SH, TODAY, "https://example.com/notice"));
        return new Candidate(notice, attach(notice, "모집공고문.pdf", AttachmentType.ANNOUNCEMENT, 0), complexName);
    }

    /** 워커가 원문 대조를 모두 통과시킨 추출 결과. 신청자격은 확인되지 않았다. */
    private ObjectNode passedExtraction(Candidate candidate) {
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
                .put("rentalType", "HAPPY_HOUSING").put("recruitmentType", "NEW").put("eligibilityVerified", false);
        review.putArray("reasons");
        review.putObject("evidence");
        review.putObject("answers");
        return tree;
    }

    /** 공고문 첨부로 넣은 서버 추출 작업이 이 결과로 방금 끝난 상태. */
    private long succeededJob(Candidate candidate, ObjectNode tree) {
        long job = extractions.requestBatch(candidate.notice().getId(), candidate.pdf().getId());
        jdbc.sql("""
                UPDATE ai_jobs SET status='SUCCEEDED', completed_at=now(), pdf=NULL, result=cast(? as jsonb) WHERE id=?
                """)
                .params(json.writeValueAsString(Map.of("json", tree.toString())), job).update();
        return job;
    }

    private List<Map<String, Object>> decisionsOf(long job) {
        return jdbc.sql("SELECT outcome, reason, eligibility_status FROM pdf_publication_decisions WHERE job_id=?")
                .param(job).query().listOfRows();
    }

    private boolean published(Announcement notice) {
        return announcements.findById(notice.getId()).orElseThrow().isPublished();
    }

    private record Candidate(Announcement notice, AnnouncementAttachment pdf, String complexName) { }

    private Announcement announcement(LocalDate applicationEnd) {
        return announcements.saveAndFlush(Announcement.create(UUID.randomUUID().toString(), null, null, "자동 추출 공고",
                AnnouncementPublicationType.ORIGINAL, RentalType.HAPPY_HOUSING, RecruitmentType.NEW, AgencyCode.LH,
                applicationEnd.minusDays(10), applicationEnd.minusDays(5), applicationEnd, applicationEnd.plusDays(10),
                "https://apply.lh.or.kr/notice", null, 0, null, null, null));
    }

    private AnnouncementAttachment attach(Announcement announcement, String fileName, AttachmentType type, int order) {
        return attachments.saveAndFlush(AnnouncementAttachment.create(announcement, fileName, type,
                "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=" + UUID.randomUUID(), order));
    }

    private static TemporaryAttachment file(byte[] bytes) throws IOException {
        TemporaryAttachment file = new TemporaryAttachment(() -> { });
        file.append(ByteBuffer.wrap(bytes));
        return file;
    }

    private static byte[] pdf() throws IOException {
        return pdf(1);
    }

    /** 쪽 수가 다르면 내용이 다른 PDF다. */
    private static byte[] pdf(int pages) throws IOException {
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
