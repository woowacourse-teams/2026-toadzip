package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.TemporaryAttachment;
import com.toadzip.backend.announcement.exception.PdfExtractionInProgressException;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementSearchRepository;
import com.toadzip.backend.announcement.repository.external.AnnouncementAttachmentClient;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "announcement.pdf-auto-extraction.enabled=true",
        "announcement.pdf-auto-extraction.initial-delay=PT24H",
        "announcement.pdf-auto-extraction.max-in-flight=2"
})
@ActiveProfiles("test")
class PdfAutoExtractionSchedulerIntegrationTest {
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired private PdfAutoExtractionScheduler scheduler;
    @Autowired private AnnouncementPdfExtractionService extractions;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private AnnouncementSearchRepository search;
    @Autowired private AnnouncementAttachmentRepository attachments;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private AnnouncementAttachmentClient client;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.sql("TRUNCATE ai_jobs, announcements CASCADE").update();
        byte[] pdf = pdf();
        doAnswer(invocation -> file(pdf)).when(client).read(anyString());
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
    void 관리자가_같은_공고를_요청하면_기다리는_서버_작업이_관리자_작업이_되어_먼저_처리된다() {
        Announcement open = announcement(TODAY.plusDays(5));
        AnnouncementAttachment notice = attach(open, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
        scheduler.enqueueDue();

        assertThatThrownBy(() -> extractions.requestAttachment(open.getId(), "admin", notice.getId()))
                .isInstanceOf(PdfExtractionInProgressException.class);

        assertThat(jobs()).singleElement().satisfies(job -> {
            assertThat(job.get("request_source")).isEqualTo("ADMIN");
            assertThat(job.get("requested_by")).isEqualTo("admin");
        });
    }

    @Test
    void 관리자가_다른_PDF를_요청하면_기다리는_서버_작업을_관리자_작업으로_바꾸지_않는다() throws IOException {
        Announcement open = announcement(TODAY.plusDays(5));
        attach(open, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
        AnnouncementAttachment correction = attach(open, "정정 공고문.pdf", AttachmentType.ETC, 1);
        scheduler.enqueueDue();

        assertThatThrownBy(() -> extractions.requestAttachment(open.getId(), "admin", correction.getId()))
                .isInstanceOf(PdfExtractionInProgressException.class);
        byte[] uploaded = pdf(2);
        assertThatThrownBy(() -> extractions.request(open.getId(), "admin", "공고문.pdf", uploaded))
                .isInstanceOf(PdfExtractionInProgressException.class);

        assertThat(jobs()).singleElement().satisfies(job -> {
            assertThat(job.get("file_name")).isEqualTo("공고문.pdf");
            assertThat(job.get("request_source")).isEqualTo("BATCH");
            assertThat(job.get("requested_by")).isNull();
        });
    }

    private List<Map<String, Object>> jobs() {
        return jdbc.sql("SELECT announcement_id, file_name, request_source, requested_by, status FROM ai_jobs ORDER BY id")
                .query().listOfRows();
    }

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
