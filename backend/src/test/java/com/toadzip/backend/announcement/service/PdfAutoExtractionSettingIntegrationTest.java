package com.toadzip.backend.announcement.service;

import static com.toadzip.backend.announcement.service.PdfPublicationFixture.TODAY;
import static com.toadzip.backend.announcement.service.PdfPublicationFixture.file;
import static com.toadzip.backend.announcement.service.PdfPublicationFixture.pdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.PdfExtractionInput.PublicationMode;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.dto.response.PdfAutoExtractionStatus;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.external.AnnouncementAttachmentClient;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 환경 변수 기본값은 꺼짐이다. 관리자가 DB에 남긴 값이 서버를 다시 띄우지 않고 다음 주기의 스케줄러에 반영되는지 본다. */
@SpringBootTest(properties = {
        "announcement.pdf-auto-extraction.enabled=false",
        "announcement.pdf-publication.mode=SHADOW",
        "announcement.pdf-auto-extraction.initial-delay=PT24H",
        "announcement.pdf-auto-extraction.max-in-flight=3"
})
@ActiveProfiles("test")
@Import(PdfPublicationFixture.class)
class PdfAutoExtractionSettingIntegrationTest {
    @Autowired private PdfAutoExtractionScheduler scheduler;
    @Autowired private PdfAutoExtractionSettingService settings;
    @Autowired private PdfPublicationFixture fixture;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private AnnouncementAttachmentClient client;

    @BeforeEach
    void setUp() throws IOException {
        clear();
        byte[] content = pdf(1);
        doAnswer(invocation -> file(content)).when(client).read(anyString());
    }

    /** 같은 DB를 쓰는 다른 테스트 컨텍스트가 환경 변수 기본값을 읽도록 행을 남기지 않는다. */
    @AfterEach
    void clear() {
        jdbc.sql("TRUNCATE ai_jobs, announcements, pdf_auto_extraction_settings CASCADE").update();
    }

    @Test
    void 관리자가_바꾼_적이_없으면_환경_변수_기본값대로_꺼져_있어_작업을_넣지_않는다() {
        openWithPdf();
        openWithPdf();

        scheduler.enqueueDue();

        assertThat(batchJobs()).isZero();
        PdfAutoExtractionStatus status = settings.status();
        assertThat(status.enabled()).isFalse();
        assertThat(status.updatedBy()).isNull();
        assertThat(status.updatedAt()).isNull();
        assertThat(status.publicationMode()).isEqualTo(PublicationMode.SHADOW);
        assertThat(status.notExtracted()).isEqualTo(2);
        assertThat(status.inFlight()).isZero();
        assertThat(status.maxInFlight()).isEqualTo(3);
    }

    @Test
    void 켜면_다시_띄우지_않고_다음_주기에_작업을_넣고_바꾼_사람과_시각을_남긴다() {
        openWithPdf();
        openWithPdf();
        Instant before = Instant.now();

        PdfAutoExtractionStatus changed = settings.change(true, "admin-7");

        assertThat(changed.enabled()).isTrue();
        assertThat(changed.updatedBy()).isEqualTo("admin-7");
        assertThat(changed.updatedAt()).isBetween(before, Instant.now());
        scheduler.enqueueDue();
        assertThat(batchJobs()).isEqualTo(2);
        PdfAutoExtractionStatus status = settings.status();
        assertThat(status.inFlight()).isEqualTo(2);
        assertThat(status.notExtracted()).isZero();
    }

    @Test
    void 켰다가_끄면_다음_주기부터_넣지_않고_나중에_바꾼_사람이_남는다() {
        openWithPdf();
        settings.change(true, "admin-7");
        scheduler.enqueueDue();
        openWithPdf();

        PdfAutoExtractionStatus changed = settings.change(false, "admin-9");
        scheduler.enqueueDue();

        assertThat(batchJobs()).isEqualTo(1);
        assertThat(changed.enabled()).isFalse();
        assertThat(changed.updatedBy()).isEqualTo("admin-9");
        assertThat(jdbc.sql("SELECT count(*) FROM pdf_auto_extraction_settings").query(Long.class).single())
                .isEqualTo(1);
    }

    private long batchJobs() {
        return jdbc.sql("SELECT count(*) FROM ai_jobs WHERE request_source = 'BATCH'").query(Long.class).single();
    }

    private void openWithPdf() {
        Announcement notice = announcements.saveAndFlush(Announcement.create(UUID.randomUUID().toString(), null, null,
                "자동 추출 공고", AnnouncementPublicationType.ORIGINAL, RentalType.HAPPY_HOUSING, RecruitmentType.NEW,
                AgencyCode.LH, TODAY.minusDays(5), TODAY, TODAY.plusDays(5), TODAY.plusDays(15),
                "https://apply.lh.or.kr/notice", null, 0, null, null, null));
        fixture.attach(notice, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
    }
}
