package com.toadzip.backend.announcement.service;

import static com.toadzip.backend.announcement.service.PdfPublicationFixture.TODAY;
import static com.toadzip.backend.announcement.service.PdfPublicationFixture.file;
import static com.toadzip.backend.announcement.service.PdfPublicationFixture.pdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.toadzip.backend.announcement.controller.PdfPublicationReviewController;
import com.toadzip.backend.announcement.controller.PdfPublicationReviewController.ReviewPage;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.PdfExtractionInput.PublicationMode;
import com.toadzip.backend.announcement.domain.PdfExtractionJob;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRowSeed;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException;
import com.toadzip.backend.announcement.exception.AttachmentUnavailableException.Reason;
import com.toadzip.backend.announcement.exception.PdfExtractionInProgressException;
import com.toadzip.backend.announcement.repository.AnnouncementComplexRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementScheduleRepository;
import com.toadzip.backend.announcement.repository.AnnouncementSearchRepository;
import com.toadzip.backend.announcement.repository.PdfExtractionJobRepository;
import com.toadzip.backend.announcement.repository.PdfPublicationDecisions;
import com.toadzip.backend.announcement.repository.PdfPublicationDecisions.ReviewItem;
import com.toadzip.backend.announcement.repository.external.AnnouncementAttachmentClient;
import com.toadzip.backend.announcement.service.PdfPublicationFixture.Candidate;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {
        "announcement.pdf-auto-extraction.enabled=true",
        "announcement.pdf-publication.mode=APPLY",
        "app.notification.publisher-enabled=true", "app.notification.activation-at=2026-01-01T00:00:00Z",
        "announcement.pdf-auto-extraction.initial-delay=PT24H",
        "announcement.pdf-auto-extraction.max-in-flight=2"
})
// 검수함 조회가 신청자격 공개본 테이블을 읽는다. 엔티티가 아니라 테스트 스키마에 없어 운영 마이그레이션으로 만든다.
@Sql(scripts = {"/db/migration/V20261119_03__pdf_publication_decisions.sql",
        "/db/migration/V20261110_04__basic_comparison_current_content.sql"},
        executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(statements = {"DROP TABLE pdf_publication_decisions", "DROP INDEX ix_ai_jobs_publication_pending",
        "DROP TABLE basic_comparison_profiles", "DROP TABLE basic_comparison_rule_sets"},
        executionPhase = ExecutionPhase.AFTER_TEST_CLASS)
@ActiveProfiles("test")
@Import(PdfPublicationFixture.class)
class PdfAutoExtractionSchedulerIntegrationTest {
    private static final Path WORKER_RESULT_EXAMPLE =
            Path.of("../ai-worker/contract/announcement-pdf-extraction/result.example.json");

    @Autowired private PdfPublicationScheduler publication;
    @Autowired private PdfPublicationService service;
    @Autowired private ObjectMapper json;
    @Autowired private PdfPublicationFixture fixture;
    @Autowired private PdfAutoExtractionScheduler scheduler;
    @Autowired private AnnouncementPdfExtractionService extractions;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private AnnouncementSearchRepository search;
    @Autowired private AnnouncementComplexRepository announcementComplexes;
    @Autowired private JdbcClient jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private PdfPublicationReviewController reviews;
    @Autowired private AnnouncementScheduleRepository schedules;
    @Autowired private IngestExecutionOwnershipService ownership;
    @MockitoBean private AnnouncementAttachmentClient client;
    /** 스파이는 판정 중의 실제 DB 호출 지점에서 일시 오류나 동시 변경을 일으키는 데 쓴다. */
    @MockitoSpyBean private PdfPublicationDecisions decisions;
    @MockitoSpyBean private PdfExtractionJobRepository jobs;
    @MockitoSpyBean private HousingTypeRepository housingTypes;
    @MockitoSpyBean private DataPipelineExecutionLock executionLock;

    /** 첨부 서버가 돌려주는 PDF. 만들 때마다 문서 ID가 달라지므로 같은 PDF를 올리는 경우는 이 바이트를 쓴다. */
    private byte[] attachmentPdf;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.sql("TRUNCATE ai_jobs, announcements CASCADE").update();
        attachmentPdf = pdf(1);
        doAnswer(invocation -> file(attachmentPdf)).when(client).read(anyString());
    }

    @Test
    void 마감_전에_추출한_적_없는_공고만_공고문_PDF로_서버_작업을_넣는다() {
        Announcement open = announcement(TODAY.plusDays(5));
        fixture.attach(open, "신청서 양식.pdf", AttachmentType.ETC, 0);
        fixture.attach(open, "입주자모집 안내.pdf", AttachmentType.ANNOUNCEMENT, 1);
        fixture.attach(announcement(TODAY.minusDays(10)), "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
        Announcement confirmed = announcement(TODAY.plusDays(5));
        fixture.attach(confirmed, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
        jdbc.sql("UPDATE announcements SET supply_confirmed_at = now() WHERE id = :id")
                .param("id", confirmed.getId()).update();
        fixture.attach(announcement(TODAY.plusDays(5)), "공고문.hwp", AttachmentType.ANNOUNCEMENT, 0);

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
            fixture.attach(announcement(TODAY.plusDays(5)), "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
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
        fixture.attach(announcement(TODAY.plusDays(5)), "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);

        scheduler.enqueueDue();
        scheduler.enqueueDue();

        assertThat(jobs()).isEmpty();
        verify(client, times(1)).read(anyString());
    }

    @Test
    void 건너뛸_공고가_후보_수를_다_차지해도_그보다_오래된_공고를_후보로_고른다() {
        Announcement older = announcement(TODAY.plusDays(5));
        fixture.attach(older, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
        Announcement skipped = announcement(TODAY.plusDays(5));
        fixture.attach(skipped, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);

        assertThat(search.findOpenNotExtracted(TODAY, Set.of(skipped.getId()), 1)).containsExactly(older.getId());
        assertThat(search.findOpenNotExtracted(TODAY, Set.of(), 1)).containsExactly(skipped.getId());
    }

    @Test
    void 관리자가_같은_첨부를_요청하면_기다리는_서버_작업이_관리자_작업이_되어_먼저_처리된다() {
        Announcement open = announcement(TODAY.plusDays(5));
        AnnouncementAttachment notice = fixture.attach(open, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
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
        fixture.attach(open, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
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
        fixture.attach(open, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
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
        fixture.attach(open, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
        AnnouncementAttachment correction = fixture.attach(open, "공고문.pdf", AttachmentType.ETC, 1);
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
        Candidate candidate = fixture.candidate();
        Announcement notice = candidate.notice();
        if (scenario.equals("published")) {
            jdbc.sql("UPDATE announcements SET release_status='PUBLISHED' WHERE id=?").param(notice.getId()).update();
        }
        ObjectNode tree = fixture.passedExtraction(candidate);
        if (scenario.equals("conflict")) {
            tree.withObject("publicationReview").withArray("reasons").add("7쪽 일정 예외");
        }
        long job = fixture.succeededJob(candidate, tree);
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
        if (scenario.equals("changedPdf")) {
            assertThat(result.get("reason")).isEqualTo("공고문 PDF 내용이 변경되었습니다.");
        }
        assertThat(published(notice)).isEqualTo(scenario.equals("apply") || scenario.equals("published"));
        assertThat(jdbc.sql("SELECT count(*) FROM notification_outbox_events WHERE announcement_id=?")
                .param(notice.getId()).query(Integer.class).single()).isEqualTo(scenario.equals("apply") ? 1 : 0);
    }

    @Test
    void 기관_서버가_잠깐_응답하지_않으면_판정을_미루고_다음_실행에서_공개한다() {
        Candidate candidate = fixture.candidate();
        long job = fixture.succeededJob(candidate, fixture.passedExtraction(candidate));
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
        Candidate candidate = fixture.candidate();
        long job = fixture.succeededJob(candidate, fixture.passedExtraction(candidate));
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
        Candidate candidate = fixture.candidate();
        long job = fixture.succeededJob(candidate, fixture.passedExtraction(candidate));
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
        Candidate candidate = fixture.candidate();
        long job = fixture.succeededJob(candidate, fixture.passedExtraction(candidate));
        extractions.request(candidate.notice().getId(), "admin", "정정 공고문.pdf", pdf(2));

        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision -> {
            assertThat(decision.get("outcome")).isEqualTo("REVIEW");
            assertThat(decision.get("reason")).isEqualTo("공고·원천이 변경되었거나 이미 검토 중입니다.");
        });
        assertThat(published(candidate.notice())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 정정공고도_공고문_PDF가_원문과_맞으면_자동으로_보정해_공개한다(boolean correctionNoticeAttached) {
        // 정정공고는 정정 내용을 반영한 공고문을 다시 낸다. 정정 내역 문서가 따로 붙어도 판정은 공고문 PDF로 한다(사용자 결정: "정정도 자동으로").
        Candidate candidate = fixture.candidate();
        long id = candidate.notice().getId();
        change(id, "status = 'CORRECTION'");
        if (correctionNoticeAttached) {
            fixture.attach(candidate.notice(), "정정내역.pdf", AttachmentType.CORRECTION, 1);
        }
        long job = fixture.succeededJob(candidate, fixture.passedExtraction(candidate));

        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision ->
                assertThat(decision.get("outcome")).as("%s", decision.get("reason")).isEqualTo("PUBLISHED"));
        assertThat(published(candidate.notice())).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            differentEndDate     | 기존 값과 PDF가 다릅니다: applicationEndDate
            existingSupplyRows   | 기존 공급표가 있어 교체·삭제 검토가 필요합니다.
            versionRaised        | 공고·원천이 변경되었거나 이미 검토 중입니다.
            adminModified        | 공고·원천이 변경되었거나 이미 검토 중입니다.
            supplyConfirmed      | 공고·원천이 변경되었거나 이미 검토 중입니다.
            cancelled            | 공고·원천이 변경되었거나 이미 검토 중입니다.
            correctionOnlyPdf    | 정정·취소 첨부를 확인하세요.
            correctionCancelled  | 정정·취소 첨부를 확인하세요.
            correctionAttached   | 정정·취소 첨부를 확인하세요.
            cancellationAttached | 정정·취소 첨부를 확인하세요.
            """)
    void 이미_값이_있거나_작업_뒤에_바뀐_공고는_보류하고_기존_값을_그대로_둔다(String scenario, String reason) {
        Candidate candidate = fixture.candidate();
        long id = candidate.notice().getId();
        if (scenario.equals("differentEndDate")) {
            jdbc.sql("UPDATE announcements SET application_end_date = ? WHERE id = ?")
                    .params(TODAY.plusDays(3), id).update();
        }
        if (scenario.equals("existingSupplyRows")) {
            unconfirmedSupplyRow(candidate);
        }
        long job = fixture.succeededJob(candidate, fixture.passedExtraction(candidate));
        switch (scenario) {
            case "versionRaised" -> change(id, "version = version + 1");
            case "adminModified" -> change(id, "admin_modified = true");
            case "supplyConfirmed" -> change(id, "supply_confirmed_at = now()");
            case "cancelled" -> change(id, "status = 'CANCELLATION'");
            case "correctionOnlyPdf" -> {
                // 정정공고에 공고문 종류 PDF가 없어 정정 내역 문서를 추출한 경우다. 정정 내역만으로는 공고를 채우지 않는다.
                change(id, "status = 'CORRECTION'");
                jdbc.sql("UPDATE announcement_attachments SET file_type = 'CORRECTION' WHERE announcement_id = ?")
                        .param(id).update();
            }
            case "correctionCancelled" -> {
                change(id, "status = 'CORRECTION'");
                fixture.attach(candidate.notice(), "취소공고문.pdf", AttachmentType.CANCELLATION, 1);
            }
            case "correctionAttached" ->
                    fixture.attach(candidate.notice(), "정정공고문.pdf", AttachmentType.CORRECTION, 1);
            case "cancellationAttached" ->
                    fixture.attach(candidate.notice(), "취소공고문.pdf", AttachmentType.CANCELLATION, 1);
            default -> { }
        }
        Map<String, Object> before = announcementRow(id);
        List<Map<String, Object>> supplyBefore = supplyRows(id);

        publication.process();
        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision -> {
            assertThat(decision.get("outcome")).isEqualTo("REVIEW");
            assertThat(decision.get("reason")).isEqualTo(reason);
        });
        assertThat(published(candidate.notice())).isFalse();
        assertThat(announcementRow(id)).isEqualTo(before);
        assertThat(supplyRows(id)).isEqualTo(supplyBefore);
        assertThat(count("notification_outbox_events", id)).isZero();
    }

    @Test
    void 수집한_유형과_날짜를_작업_입력에_담고_PDF가_같은_값이면_공급표를_확정한다() {
        Candidate collected = fixture.candidate(collectedNotice(RentalType.HAPPY_HOUSING, RecruitmentType.NEW));
        long id = collected.notice().getId();
        long job = fixture.succeededJob(collected, fixture.passedExtraction(collected));
        Candidate unconfirmed = fixture.candidate();
        long unconfirmedJob = fixture.succeededJob(unconfirmed, heldExtraction(unconfirmed));

        // 워커가 읽는 모양이다: 유형은 상수 이름, 날짜는 YYYY-MM-DD, 없는 값은 null.
        assertThat(publicationInput(job)).isEqualTo(json.readTree("""
                {"attachmentId": %d, "announcementVersion": %d, "title": "수집값이 있는 공고", "mode": "APPLY",
                 "rentalType": "HAPPY_HOUSING", "recruitmentType": "NEW", "applicationStartDate": "%s",
                 "applicationEndDate": "%s", "winnerAnnouncementDate": "%s"}
                """.formatted(collected.pdf().getId(), collected.notice().getVersion(), TODAY, TODAY.plusDays(2),
                TODAY.plusDays(5))));
        JsonNode withoutValues = publicationInput(unconfirmedJob);
        assertThat(List.of("rentalType", "recruitmentType", "applicationStartDate", "applicationEndDate",
                "winnerAnnouncementDate")).allSatisfy(field -> assertThat(withoutValues.required(field).isNull())
                .as(field).isTrue());

        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision ->
                assertThat(decision.get("outcome")).as("%s", decision.get("reason")).isEqualTo("CONFIRMED"));
        Announcement notice = announcements.findById(id).orElseThrow();
        assertThat(notice.isSupplyConfirmed()).isTrue();
        assertThat(notice.getSupplyType()).isEqualTo(RentalType.HAPPY_HOUSING);
        assertThat(notice.getRecruitmentType()).isEqualTo(RecruitmentType.NEW);
        assertThat(List.of(notice.getApplicationStartDate(), notice.getApplicationEndDate(),
                notice.getWinnerAnnouncementDate())).containsExactly(TODAY, TODAY.plusDays(2), TODAY.plusDays(5));
        assertThat(supplyRows(id)).singleElement()
                .satisfies(row -> assertThat(row.get("housing_type_name")).isEqualTo("36A"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            1 | PUBLISHED | 공고 보정 완료
            0 | REVIEW    | 공급표에 단지명이 없고 공고에 연결된 단지가 한 곳이 아닙니다(0곳).
            2 | REVIEW    | 공급표에 단지명이 없고 공고에 연결된 단지가 한 곳이 아닙니다(2곳).
            """)
    void 단지명이_없는_공급표는_공고에_연결된_단지가_한_곳일_때만_그_단지로_채운다(int linked, String outcome, String reason) {
        Candidate candidate = fixture.candidate();
        long id = candidate.notice().getId();
        List<String> names = List.of(candidate.complexName(), "다른 단지").subList(0, linked);
        names.forEach(name -> announcementComplexes.saveAndFlush(
                AnnouncementComplex.observe(candidate.notice(), name, null, null)));
        ObjectNode tree = fixture.passedExtraction(candidate);
        tree.withObject("data").withObject("supplySheet").withArray("housingTypes")
                .forEach(type -> ((ObjectNode) type).put("complexName", ""));
        long job = fixture.succeededJob(candidate, tree);

        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision -> {
            assertThat(decision.get("outcome")).as("%s", decision.get("reason")).isEqualTo(outcome);
            assertThat(decision.get("reason").toString()).startsWith(reason);
        });
        assertThat(published(candidate.notice())).isEqualTo(linked == 1);
        List<String> filled = jdbc.sql("""
                SELECT c.source_complex_name FROM supply_rows r
                JOIN announcement_complexes c ON c.id = r.announcement_complex_id WHERE r.announcement_id = ?
                """).param(id).query(String.class).list();
        if (linked == 1) {
            assertThat(filled).containsExactly(candidate.complexName());
        } else {
            assertThat(filled).isEmpty();
        }
        // 보류한 공고에 공고 단지를 새로 만들지 않는다.
        assertThat(jdbc.sql("SELECT source_complex_name FROM announcement_complexes WHERE announcement_id = ?")
                .param(id).query(String.class).list()).containsExactlyInAnyOrderElementsOf(names);
    }

    @Test
    void 워커_계약_예시_모양의_추출_결과를_보정안으로_읽어_공급표까지_공개한다() throws IOException {
        Candidate candidate = fixture.candidate();
        long id = candidate.notice().getId();
        long job = fixture.succeededJob(candidate, workerResult(candidate));

        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision ->
                assertThat(decision.get("outcome")).as("%s", decision.get("reason")).isEqualTo("PUBLISHED"));
        Announcement notice = announcements.findById(id).orElseThrow();
        assertThat(notice.isPublished()).isTrue();
        assertThat(notice.getSupplyType()).isEqualTo(RentalType.HAPPY_HOUSING);
        assertThat(notice.getRecruitmentType()).isEqualTo(RecruitmentType.NEW);
        assertThat(notice.getApplicationEndDate()).isEqualTo(TODAY.plusDays(2));
        assertThat(jdbc.sql("SELECT housing_type_name FROM supply_rows WHERE announcement_id = ?").param(id)
                .query(String.class).list()).containsExactly("36A");
        assertThat(jdbc.sql("SELECT label FROM announcement_supply_targets WHERE announcement_id = ?").param(id)
                .query(String.class).list()).containsExactly("청년");
        assertThat(jdbc.sql("SELECT household_count FROM supply_quotas WHERE announcement_id = ?").param(id)
                .query(Integer.class).list()).containsExactly(78);
        assertThat(count("announcement_reception_places", id)).isOne();
        assertThat(count("announcement_schedules", id)).isOne();
    }

    @Test
    void 검수함은_운영자가_볼_것이_남은_공고만_새_작업부터_보인다() throws IOException {
        Candidate held = fixture.candidate();
        long heldJob = fixture.succeededJob(held, heldExtraction(held));
        Candidate shadow = fixture.candidate();
        long shadowJob = fixture.succeededJob(shadow, fixture.passedExtraction(shadow));
        jdbc.sql("UPDATE ai_jobs SET input=jsonb_set(input, '{publication,mode}', '\"SHADOW\"') WHERE id=?")
                .param(shadowJob).update();
        Candidate eligibilityLeft = fixture.candidate();
        long eligibilityLeftJob = fixture.succeededJob(eligibilityLeft, fixture.passedExtraction(eligibilityLeft));
        Candidate eligibilityPublished = fixture.candidate();
        fixture.succeededJob(eligibilityPublished, fixture.passedExtraction(eligibilityPublished));
        Candidate replaced = fixture.candidate();
        fixture.succeededJob(replaced, heldExtraction(replaced));
        Candidate confirmed = fixture.candidate();
        fixture.succeededJob(confirmed, heldExtraction(confirmed));

        publication.process();
        // 운영자가 신청자격을 공개했거나, 다른 PDF로 다시 추출했거나, 공급을 직접 확정한 공고는 더 볼 것이 없다.
        jdbc.sql("""
                INSERT INTO basic_comparison_rule_sets(announcement_id, status, version, content, published_at)
                VALUES (?, 'PUBLISHED', 1, '{}', now())
                """).param(eligibilityPublished.notice().getId()).update();
        extractions.request(replaced.notice().getId(), "admin", "정정 공고문.pdf", pdf(2));
        change(confirmed.notice().getId(), "supply_confirmed_at = now()");

        ReviewPage page = reviews.reviews(null).data();

        assertThat(page.items()).extracting(ReviewItem::jobId, ReviewItem::announcementId, ReviewItem::eligibilityOnly)
                .containsExactly(
                        tuple(eligibilityLeftJob, eligibilityLeft.notice().getId(), true),
                        tuple(shadowJob, shadow.notice().getId(), false),
                        tuple(heldJob, held.notice().getId(), false));
        assertThat(page.items()).extracting(ReviewItem::reason).containsExactly(
                "공고 보정 완료 · 신청자격을 추출하지 못했습니다. 신청자격을 확인하세요.",
                "공개 기준 통과 · 모의 판정", "7쪽 일정 예외");
        assertThat(page.nextBefore()).isNull();
    }

    @Test
    void 검수함은_한_쪽을_넘으면_다음_쪽_기준을_주고_그_기준으로_나머지를_이어_보인다() {
        List<Long> jobs = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            Candidate candidate = fixture.candidate();
            jobs.add(fixture.succeededJob(candidate, heldExtraction(candidate)));
        }
        for (int run = 0; run < 3; run++) {
            publication.process();
        }

        ReviewPage first = reviews.reviews(null).data();
        ReviewPage second = reviews.reviews(first.nextBefore()).data();

        assertThat(first.items()).extracting(ReviewItem::jobId)
                .containsExactlyElementsOf(jobs.reversed().subList(0, 20));
        assertThat(first.nextBefore()).isEqualTo(jobs.get(1));
        assertThat(second.items()).extracting(ReviewItem::jobId).containsExactly(jobs.getFirst());
        assertThat(second.nextBefore()).isNull();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 신청자격이_확인되지_않으면_공고만_공개하고_신청자격은_검수로_남긴다() {
        Candidate candidate = fixture.candidate();
        long job = fixture.succeededJob(candidate, fixture.passedExtraction(candidate));

        service.process(job, PublicationMode.APPLY);

        assertThat(published(candidate.notice())).isTrue();
        assertThat(decisionsOf(job)).singleElement()
                .satisfies(decision -> assertThat(decision.get("eligibility_status")).isEqualTo("REVIEW"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 신청자격까지_확인된_공고는_공개하고_신청자격_자동_공개를_기다린다() {
        Candidate candidate = fixture.candidate();
        ObjectNode tree = fixture.passedExtraction(candidate);
        tree.withObject("publicationReview").put("eligibilityPublishable", true);
        long job = fixture.succeededJob(candidate, tree);

        service.process(job, PublicationMode.APPLY);

        assertThat(published(candidate.notice())).isTrue();
        assertThat(decisionsOf(job)).singleElement()
                .satisfies(decision -> assertThat(decision.get("eligibility_status")).isEqualTo("PENDING"));
    }

    @Test
    void 공고문을_다시_받는_동안에는_DB_트랜잭션을_잡지_않는다() {
        Candidate candidate = fixture.candidate();
        long job = fixture.succeededJob(candidate, fixture.passedExtraction(candidate));
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

    @Test
    void 기존_일정과_같은_일정은_더하지_않고_그_밖의_PDF_일정만_한_번씩_더한다() {
        Candidate candidate = fixture.candidate();
        long id = candidate.notice().getId();
        // 수집 일정은 이름이 고정이고 시각이 없어 PDF 일정과 글자 그대로 같지 않다.
        schedule(candidate.notice(), ScheduleType.APPLICATION, "접수", TODAY, TODAY.plusDays(2), 0);
        schedule(candidate.notice(), ScheduleType.ETC, "서류제출 대상자 발표", TODAY.plusDays(4), TODAY.plusDays(4), 1);
        List<Map<String, Object>> collected = scheduleRows(id);
        ObjectNode tree = fixture.passedExtraction(candidate);
        ObjectNode data = tree.withObject("data");
        pdfSchedule(data, "APPLICATION", "현장 신청접수 10:00~15:00", TODAY.plusDays(2), TODAY.plusDays(2));
        pdfSchedule(data, "DOCUMENT_SUBMISSION", "서류제출대상자 발표", TODAY.plusDays(4), TODAY.plusDays(4));
        pdfSchedule(data, "ETC", "인터넷 접수 안내", TODAY.plusDays(1), TODAY.plusDays(1));
        pdfSchedule(data, "CONTRACT", "계약 체결", TODAY.plusDays(1), TODAY.plusDays(1));
        pdfSchedule(data, "APPLICATION", "추가 접수", TODAY.plusDays(3), TODAY.plusDays(3));
        pdfSchedule(data, "MOVE_IN", "입주", TODAY.plusDays(30), TODAY.plusDays(40));
        pdfSchedule(data, "MOVE_IN", "입주", TODAY.plusDays(30), TODAY.plusDays(40));
        long job = fixture.succeededJob(candidate, tree);

        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision ->
                assertThat(decision.get("outcome")).as("%s", decision.get("reason")).isEqualTo("PUBLISHED"));
        List<Map<String, Object>> stored = scheduleRows(id);
        assertThat(stored.subList(0, 2)).isEqualTo(collected);
        assertThat(stored.subList(2, stored.size())).extracting(row -> row.get("name"))
                .containsExactly("계약 체결", "추가 접수", "입주");
    }

    @Test
    void 수집한_유형이_기타이면_PDF로_확인한_유형으로_바꿔_확정한다() {
        Candidate collected = fixture.candidate(collectedNotice(RentalType.ETC, RecruitmentType.ETC));
        long id = collected.notice().getId();
        long job = fixture.succeededJob(collected, fixture.passedExtraction(collected));

        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision ->
                assertThat(decision.get("outcome")).as("%s", decision.get("reason")).isEqualTo("CONFIRMED"));
        Announcement notice = announcements.findById(id).orElseThrow();
        assertThat(notice.isSupplyConfirmed()).isTrue();
        assertThat(notice.getSupplyType()).isEqualTo(RentalType.HAPPY_HOUSING);
        assertThat(notice.getRecruitmentType()).isEqualTo(RecruitmentType.NEW);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            NATIONAL_RENTAL | NEW      | rentalType
            ETC             | WAITLIST | recruitmentType
            """)
    void 수집한_유형이_기타가_아닌_다른_값이면_보류하고_두_유형을_그대로_둔다(RentalType rentalType,
            RecruitmentType recruitmentType, String conflict) {
        Candidate collected = fixture.candidate(collectedNotice(rentalType, recruitmentType));
        long id = collected.notice().getId();
        long job = fixture.succeededJob(collected, fixture.passedExtraction(collected));

        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision -> {
            assertThat(decision.get("outcome")).isEqualTo("REVIEW");
            assertThat(decision.get("reason")).isEqualTo("기존 값과 PDF가 다릅니다: " + conflict);
        });
        // 한 항목이라도 기존 값과 다르면 덮어써도 되는 '기타'까지 그대로 둔다.
        Announcement notice = announcements.findById(id).orElseThrow();
        assertThat(notice.isSupplyConfirmed()).isFalse();
        assertThat(notice.getSupplyType()).isEqualTo(rentalType);
        assertThat(notice.getRecruitmentType()).isEqualTo(recruitmentType);
    }

    @Test
    void 적용_뒤_결정_기록이_일시_오류로_실패하면_모두_되돌리고_다음_실행에서_한_번만_적용한다() {
        Candidate candidate = fixture.candidate();
        long id = candidate.notice().getId();
        ObjectNode tree = fixture.passedExtraction(candidate);
        pdfSchedule(tree.withObject("data"), "APPLICATION", "청약 접수", TODAY, TODAY.plusDays(2));
        long job = fixture.succeededJob(candidate, tree);
        // 공고 보정을 적용한 뒤 같은 트랜잭션에서 공개 결정을 기록하는 첫 호출이 잠금 대기 초과로 실패한다.
        doThrow(new CannotAcquireLockException("lock timeout")).doCallRealMethod()
                .when(decisions).record(eq(job), eq("PUBLISHED"), anyString(), any(), any());

        publication.process();

        assertThat(decisionsOf(job)).isEmpty();
        assertThat(published(candidate.notice())).isFalse();
        assertThat(supplyRows(id)).isEmpty();
        assertThat(count("announcement_schedules", id)).isZero();
        assertThat(count("notification_outbox_events", id)).isZero();

        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision ->
                assertThat(decision.get("outcome")).as("%s", decision.get("reason")).isEqualTo("PUBLISHED"));
        assertThat(published(candidate.notice())).isTrue();
        assertThat(supplyRows(id)).hasSize(1);
        assertThat(count("announcement_schedules", id)).isOne();
        assertThat(count("notification_outbox_events", id)).isOne();
    }

    @Test
    void 보정안을_만드는_조회의_일시_오류는_판정을_기록하지_않고_다음_실행에서_공개한다() {
        Candidate candidate = fixture.candidate();
        long job = fixture.succeededJob(candidate, fixture.passedExtraction(candidate));
        // 추출 결과를 보정안으로 읽는 중에 단지의 주택형을 찾는 조회가 잠금 대기 초과로 실패한다.
        doThrow(new CannotAcquireLockException("lock timeout")).when(housingTypes).findAllByHousingComplexIdIn(any());

        publication.process();

        assertThat(decisionsOf(job)).isEmpty();
        assertThat(published(candidate.notice())).isFalse();

        reset(housingTypes);
        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision ->
                assertThat(decision.get("outcome")).as("%s", decision.get("reason")).isEqualTo("PUBLISHED"));
        assertThat(published(candidate.notice())).isTrue();
    }

    @Test
    void 일시_오류가_작업_완료_후_한_시간을_넘기면_적용을_되돌리고_검수에_넘긴다() {
        Candidate candidate = fixture.candidate();
        long id = candidate.notice().getId();
        long job = fixture.succeededJob(candidate, fixture.passedExtraction(candidate));
        jdbc.sql("UPDATE ai_jobs SET completed_at = now() - interval '2 hours' WHERE id=?").param(job).update();
        doThrow(new CannotAcquireLockException("lock timeout"))
                .when(decisions).record(eq(job), eq("PUBLISHED"), anyString(), any(), any());

        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision -> {
            assertThat(decision.get("outcome")).isEqualTo("REVIEW");
            assertThat(decision.get("reason")).isEqualTo("자동 보정 실패 · 원문과 변경안을 확인하세요.");
        });
        assertThat(published(candidate.notice())).isFalse();
        assertThat(supplyRows(id)).isEmpty();
    }

    @Test
    void 수집_실행_소유권을_확인하다_난_실패는_판정을_기록하지_않고_다음_실행에서_공개한다() {
        Candidate candidate = fixture.candidate();
        long job = fixture.succeededJob(candidate, fixture.passedExtraction(candidate));
        // 잠금용 DB 연결을 얻지 못하면 소유권 잠금이 이 예외로 감싸 던지고, 저장소 예외 변환을 거쳐 판정 서비스에 닿는다.
        doThrow(new IllegalStateException("데이터 수집·정제 실행 잠금을 처리하지 못했습니다.")).doCallRealMethod()
                .when(executionLock).tryAcquire();

        publication.process();

        assertThat(decisionsOf(job)).isEmpty();
        assertThat(published(candidate.notice())).isFalse();

        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision ->
                assertThat(decision.get("outcome")).as("%s", decision.get("reason")).isEqualTo("PUBLISHED"));
    }

    @Test
    void 일시_오류_뒤_작업_조회까지_실패하면_결정을_기록하지_않고_남은_작업을_이어서_판정한다() {
        Candidate unreachable = fixture.candidate();
        long unreachableJob = fixture.succeededJob(unreachable, fixture.passedExtraction(unreachable));
        // 재시도 기간이 지난 작업이다. 작업을 읽을 수 있었다면 검수로 넘겼을 것이다.
        jdbc.sql("UPDATE ai_jobs SET completed_at = now() - interval '2 hours' WHERE id=?")
                .param(unreachableJob).update();
        Candidate next = fixture.candidate();
        long nextJob = fixture.succeededJob(next, fixture.passedExtraction(next));
        doThrow(new CannotAcquireLockException("lock timeout"))
                .when(decisions).record(eq(unreachableJob), eq("PUBLISHED"), anyString(), any(), any());
        // 판정을 시작할 때는 작업을 읽었지만, 실패한 뒤 재시도 기간을 확인하려는 조회는 DB에 닿지 못한다.
        Optional<PdfExtractionJob> stored = jobs.findById(unreachableJob);
        doReturn(stored).doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(jobs).findById(unreachableJob);

        publication.process();

        assertThat(decisionsOf(unreachableJob)).isEmpty();
        assertThat(published(unreachable.notice())).isFalse();
        assertThat(decisionsOf(nextJob)).singleElement().satisfies(decision ->
                assertThat(decision.get("outcome")).as("%s", decision.get("reason")).isEqualTo("PUBLISHED"));
    }

    @Test
    void 보정_서비스가_입력을_거절한_내부_문구는_검수함에_보이지_않는다() {
        Candidate candidate = fixture.candidate();
        ObjectNode tree = fixture.passedExtraction(candidate);
        // 종료가 시작보다 빠른 일정은 보정 서비스가 IllegalArgumentException으로 거절한다.
        pdfSchedule(tree.withObject("data"), "APPLICATION", "청약 접수", TODAY.plusDays(2), TODAY);
        long job = fixture.succeededJob(candidate, tree);

        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision -> {
            assertThat(decision.get("outcome")).isEqualTo("REVIEW");
            assertThat(decision.get("reason")).isEqualTo("자동 보정 실패 · 원문과 변경안을 확인하세요.");
        });
        assertThat(published(candidate.notice())).isFalse();
    }

    @Test
    void 공고문을_다시_받는_동안에는_수집_실행_소유권을_잡지_않는다() {
        Candidate candidate = fixture.candidate();
        long job = fixture.succeededJob(candidate, fixture.passedExtraction(candidate));
        List<Boolean> ingestCouldStart = new ArrayList<>();
        doAnswer(invocation -> {
            try (var ingest = ownership.acquire()) {
                ingestCouldStart.add(true);
            }
            return file(attachmentPdf);
        }).when(client).read(anyString());

        publication.process();

        assertThat(ingestCouldStart).containsExactly(true);
        assertThat(decisionsOf(job)).singleElement()
                .satisfies(decision -> assertThat(decision.get("outcome")).isEqualTo("PUBLISHED"));
    }

    @Test
    void 수집이_도는_동안에는_워커_보류만_기록하고_판정은_다음_실행으로_미룬다() {
        Candidate held = fixture.candidate();
        long heldJob = fixture.succeededJob(held, heldExtraction(held));
        Candidate candidate = fixture.candidate();
        long job = fixture.succeededJob(candidate, fixture.passedExtraction(candidate));

        try (var ingest = ownership.acquire()) {
            publication.process();
        }

        assertThat(decisionsOf(heldJob)).singleElement()
                .satisfies(decision -> assertThat(decision.get("outcome")).isEqualTo("REVIEW"));
        assertThat(decisionsOf(job)).isEmpty();
        assertThat(published(candidate.notice())).isFalse();

        publication.process();

        assertThat(decisionsOf(job)).singleElement()
                .satisfies(decision -> assertThat(decision.get("outcome")).isEqualTo("PUBLISHED"));
    }

    @Test
    void 판정_중에_공고_행이_동시에_바뀌어_직렬화에_실패하면_판정을_미루고_다음_실행에서_공개한다() {
        Candidate candidate = fixture.candidate();
        long id = candidate.notice().getId();
        long job = fixture.succeededJob(candidate, fixture.passedExtraction(candidate));
        // 판정 트랜잭션이 작업을 선점해 스냅숏을 잡은 직후 다른 트랜잭션이 공고 행을 고치고 커밋한다.
        doAnswer(invocation -> {
            Object claimed = invocation.callRealMethod();
            CompletableFuture.runAsync(() -> change(id, "name = name")).join();
            return claimed;
        }).doCallRealMethod().when(decisions).claim(job);

        publication.process();

        assertThat(decisionsOf(job)).isEmpty();
        assertThat(published(candidate.notice())).isFalse();

        publication.process();

        assertThat(decisionsOf(job)).singleElement().satisfies(decision ->
                assertThat(decision.get("outcome")).as("%s", decision.get("reason")).isEqualTo("PUBLISHED"));
        assertThat(published(candidate.notice())).isTrue();
    }

    /** 유형과 날짜를 수집해 이미 공개한 공고(저장 전). 날짜는 통과한 추출 결과의 날짜와 같다. */
    private static Announcement collectedNotice(RentalType rentalType, RecruitmentType recruitmentType) {
        return Announcement.create("SH:" + UUID.randomUUID(), null, null, "수집값이 있는 공고",
                AnnouncementPublicationType.ORIGINAL, rentalType, recruitmentType, AgencyCode.SH,
                TODAY, TODAY, TODAY.plusDays(2), TODAY.plusDays(5), "https://example.com/notice", null, 0, null);
    }

    /** 수집한 일정처럼 시각 없이 날짜만 있는 기존 일정. */
    private void schedule(Announcement notice, ScheduleType type, String name, LocalDate start, LocalDate end,
            int order) {
        schedules.saveAndFlush(AnnouncementSchedule.create(notice, type, name, start.atStartOfDay(),
                end.atStartOfDay(), order));
    }

    /** 공고문에 적힌 일정. 하루 안의 시각(10시~15시)이 붙는다. */
    private static void pdfSchedule(ObjectNode data, String type, String name, LocalDate start, LocalDate end) {
        data.withArray("schedules").addObject().put("scheduleType", type).put("name", name)
                .put("startAt", start.atTime(10, 0).toString())
                .put("endAt", end.atTime(15, 0).toString());
    }

    private List<Map<String, Object>> scheduleRows(long announcementId) {
        return jdbc.sql("SELECT * FROM announcement_schedules WHERE announcement_id = ? ORDER BY display_order, id")
                .param(announcementId).query().listOfRows();
    }

    /** 워커가 보류 사유를 남긴 추출 결과. */
    private ObjectNode heldExtraction(Candidate candidate) {
        ObjectNode tree = fixture.passedExtraction(candidate);
        tree.withObject("publicationReview").withArray("reasons").add("7쪽 일정 예외");
        return tree;
    }

    /**
     * 워커 계약 예시(result.example.json)의 추출 JSON과 공개 판정에 본문 단계가 늘 내는 날짜·접수처·일정을 더한 결과.
     * 서버 단지·주택형에 이어지도록 주택형의 단지명·형명·전용면적만 이 공고 것으로 바꾼다.
     */
    private ObjectNode workerResult(Candidate candidate) throws IOException {
        String example = json.readTree(Files.readString(WORKER_RESULT_EXAMPLE)).required("json").asString();
        ObjectNode tree = (ObjectNode) json.readTree(example);
        ObjectNode data = tree.withObject("data");
        data.withObject("announcement").put("postedDate", TODAY.toString())
                .put("applicationStartDate", TODAY.toString())
                .put("applicationEndDate", TODAY.plusDays(2).toString())
                .put("winnerAnnouncementDate", TODAY.plusDays(5).toString());
        data.putArray("receptionPlaces").addObject().put("name", "LH청약플러스").put("method", "ONLINE")
                .putNull("address").putNull("contact").put("url", "https://apply.lh.or.kr").putNull("guidance");
        data.putArray("schedules").addObject().put("scheduleType", "APPLICATION").put("name", "청약 접수")
                .put("startAt", TODAY + "T10:00:00").put("endAt", TODAY.plusDays(2) + "T17:00:00");
        data.putArray("unresolvedFields");
        ObjectNode type = (ObjectNode) data.withObject("supplySheet").withArray("housingTypes").get(0);
        type.put("complexName", candidate.complexName()).put("name", "36A").put("exclusiveArea", 36.0);
        return tree;
    }

    /** 서버가 작업에 담은 자동 공개 스냅숏(ai_jobs.input.publication). */
    private JsonNode publicationInput(long job) {
        return json.readTree(jdbc.sql("SELECT input::text FROM ai_jobs WHERE id = ?").param(job).query(String.class)
                .single()).required("publication");
    }

    private List<Map<String, Object>> decisionsOf(long job) {
        return jdbc.sql("SELECT outcome, reason, eligibility_status FROM pdf_publication_decisions WHERE job_id=?")
                .param(job).query().listOfRows();
    }

    /** 공급을 확정한 적은 없는데 공급표 주택형이 이미 있는 공고로 만든다. */
    private void unconfirmedSupplyRow(Candidate candidate) {
        long id = candidate.notice().getId();
        new TransactionTemplate(transactions).executeWithoutResult(status -> entities.persist(SupplyRowSeed.create(
                entities, entities.find(Announcement.class, id), null, null, 0, candidate.complexName(), "26A", null,
                null, SupplyCategory.NEW_SUPPLY)));
        jdbc.sql("UPDATE announcements SET supply_confirmed_at = NULL WHERE id = ?").param(id).update();
    }

    private List<Map<String, Object>> supplyRows(long announcementId) {
        return jdbc.sql("SELECT * FROM supply_rows WHERE announcement_id = ? ORDER BY id").param(announcementId)
                .query().listOfRows();
    }

    private Map<String, Object> announcementRow(long id) {
        return jdbc.sql("SELECT * FROM announcements WHERE id = ?").param(id).query().singleRow();
    }

    private void change(long announcementId, String assignment) {
        jdbc.sql("UPDATE announcements SET " + assignment + " WHERE id = ?").param(announcementId).update();
    }

    private int count(String table, long announcementId) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE announcement_id = ?").param(announcementId)
                .query(Integer.class).single();
    }

    private boolean published(Announcement notice) {
        return announcements.findById(notice.getId()).orElseThrow().isPublished();
    }

    private Announcement announcement(LocalDate applicationEnd) {
        return announcements.saveAndFlush(Announcement.create(UUID.randomUUID().toString(), null, null, "자동 추출 공고",
                AnnouncementPublicationType.ORIGINAL, RentalType.HAPPY_HOUSING, RecruitmentType.NEW, AgencyCode.LH,
                applicationEnd.minusDays(10), applicationEnd.minusDays(5), applicationEnd, applicationEnd.plusDays(10),
                "https://apply.lh.or.kr/notice", null, 0, null, null, null));
    }
}
