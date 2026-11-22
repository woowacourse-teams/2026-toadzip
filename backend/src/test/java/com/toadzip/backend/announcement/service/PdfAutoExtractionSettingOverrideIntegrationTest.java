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
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.PdfExtractionJobRepository;
import com.toadzip.backend.announcement.repository.PdfPublicationDecisions;
import com.toadzip.backend.announcement.repository.external.AnnouncementAttachmentClient;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import java.io.IOException;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 환경 변수가 켜짐인 서버에서도 관리자가 끈 값이 이긴다. 새 컨텍스트를 띄우지 않도록 설정·대역 빈을
 * PdfAutoExtractionSchedulerIntegrationTest와 똑같이 둔다.
 */
@SpringBootTest(properties = {
        "announcement.pdf-auto-extraction.enabled=true",
        "announcement.pdf-publication.mode=APPLY",
        "app.notification.publisher-enabled=true", "app.notification.activation-at=2026-01-01T00:00:00Z",
        "announcement.pdf-auto-extraction.initial-delay=PT24H",
        "announcement.pdf-auto-extraction.max-in-flight=2"
})
@ActiveProfiles("test")
@Import(PdfPublicationFixture.class)
class PdfAutoExtractionSettingOverrideIntegrationTest {
    @Autowired private PdfAutoExtractionScheduler scheduler;
    @Autowired private PdfAutoExtractionSettingService settings;
    @Autowired private PdfPublicationFixture fixture;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private AnnouncementAttachmentClient client;
    @MockitoSpyBean private PdfPublicationDecisions decisions;
    @MockitoSpyBean private PdfExtractionJobRepository jobs;
    @MockitoSpyBean private HousingTypeRepository housingTypes;
    @MockitoSpyBean private DataPipelineExecutionLock executionLock;

    @BeforeEach
    void setUp() throws IOException {
        clear();
        byte[] content = pdf(1);
        doAnswer(invocation -> file(content)).when(client).read(anyString());
    }

    /** 같은 DB를 쓰는 다른 테스트가 환경 변수 기본값을 읽도록 설정 행을 남기지 않는다. */
    @AfterEach
    void clear() {
        jdbc.sql("TRUNCATE ai_jobs, announcements, pdf_auto_extraction_settings CASCADE").update();
    }

    @Test
    void 환경_변수가_켜짐이어도_관리자가_끄면_작업을_넣지_않는다() {
        Announcement notice = announcements.saveAndFlush(Announcement.create(UUID.randomUUID().toString(), null, null,
                "자동 추출 공고", AnnouncementPublicationType.ORIGINAL, RentalType.HAPPY_HOUSING, RecruitmentType.NEW,
                AgencyCode.LH, TODAY.minusDays(5), TODAY, TODAY.plusDays(5), TODAY.plusDays(15),
                "https://apply.lh.or.kr/notice", null, 0, null, null, null));
        fixture.attach(notice, "공고문.pdf", AttachmentType.ANNOUNCEMENT, 0);
        assertThat(settings.enabled()).isTrue();

        settings.change(false, "admin");
        scheduler.enqueueDue();

        assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs WHERE request_source = 'BATCH'").query(Long.class).single())
                .isZero();
        assertThat(settings.status().enabled()).isFalse();
    }
}
