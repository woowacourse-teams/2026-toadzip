package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.PdfExtractionInput;
import com.toadzip.backend.announcement.domain.PdfExtractionJob;
import com.toadzip.backend.announcement.domain.PdfExtractionJobStatus;
import com.toadzip.backend.announcement.domain.PdfExtractionJobView;
import com.toadzip.backend.announcement.domain.PdfExtractionSource;
import com.toadzip.backend.announcement.dto.response.PdfExtractionJobResponse;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementPdfException;
import com.toadzip.backend.announcement.exception.PdfExtractionInProgressException;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.PdfExtractionInputQueryRepository;
import com.toadzip.backend.announcement.repository.PdfExtractionJobRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.hibernate.exception.ConstraintViolationException;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class AnnouncementPdfExtractionServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-01T01:00:00Z");
    private static final String RESULT = """
            {"json":"{}","pages":[1],"totalPages":9,"warnings":[],
            "usage":{"promptTokens":1,"outputTokens":2,"thinkingTokens":null}}
            """;
    private static final PdfExtractionInput INPUT = new PdfExtractionInput(
            List.of(new PdfExtractionInput.LhSupplyRow(0, "부평", "16A", "16.37", "27.6", "78", "78")), List.of());

    private final AnnouncementRepository announcements = mock(AnnouncementRepository.class);
    private final PdfExtractionJobRepository jobs = mock(PdfExtractionJobRepository.class);
    private final PdfExtractionInputQueryRepository inputs = mock(PdfExtractionInputQueryRepository.class);
    private final AnnouncementPdfExtractionService service = new AnnouncementPdfExtractionService(
            announcements, jobs, inputs, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void PDF와_입력_스냅숏을_담은_대기_작업을_넣는다() throws Exception {
        byte[] pdf = AnnouncementPdfDocumentTest.pdf("cover");
        when(announcements.existsById(7L)).thenReturn(true);
        when(inputs.read(7L)).thenReturn(INPUT);
        when(jobs.saveAndFlush(any())).thenAnswer(invocation -> {
            PdfExtractionJob job = invocation.getArgument(0);
            ReflectionTestUtils.setField(job, "id", 31L);
            return job;
        });

        PdfExtractionJobResponse response = service.request(7L, "admin", "공고문.pdf", pdf);

        ArgumentCaptor<PdfExtractionJob> saved = ArgumentCaptor.forClass(PdfExtractionJob.class);
        verify(jobs).saveAndFlush(saved.capture());
        PdfExtractionJob job = saved.getValue();
        assertThat(job.getStatus()).isEqualTo(PdfExtractionJobStatus.PENDING);
        assertThat(job.getPdf()).isEqualTo(pdf);
        assertThat(job.getPdfSha256()).isEqualTo(PdfExtractionSource.of("공고문.pdf", pdf).sha256());
        assertThat(job.getRequestedBy()).isEqualTo("admin");
        assertThat(job.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(JsonMapper.builder().build().readValue(job.getInput(), PdfExtractionInput.class))
                .isEqualTo(INPUT);
        assertThat(response.jobId()).isEqualTo(31L);
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.extraction()).isNull();
    }

    @Test
    void 진행_중_작업이_있으면_새_작업을_넣지_않는다() throws Exception {
        when(announcements.existsById(7L)).thenReturn(true);
        when(jobs.existsByKindAndAnnouncementIdAndStatusIn(PdfExtractionJob.KIND, 7L, EnumSet.of(
                PdfExtractionJobStatus.PENDING, PdfExtractionJobStatus.RUNNING, PdfExtractionJobStatus.RETRY)))
                .thenReturn(true);

        assertThatThrownBy(() -> service.request(7L, "admin", "공고문.pdf", AnnouncementPdfDocumentTest.pdf("cover")))
                .isInstanceOf(PdfExtractionInProgressException.class);
        verify(jobs, never()).saveAndFlush(any());
    }

    @Test
    void 동시에_넣다가_진행_중_작업_제약에_걸리면_진행_중으로_알린다() throws Exception {
        when(announcements.existsById(7L)).thenReturn(true);
        when(inputs.read(7L)).thenReturn(INPUT);
        when(jobs.saveAndFlush(any())).thenThrow(violation("ux_ai_jobs_active"));

        assertThatThrownBy(() -> service.request(7L, "admin", "공고문.pdf", AnnouncementPdfDocumentTest.pdf("cover")))
                .isInstanceOf(PdfExtractionInProgressException.class);
    }

    @Test
    void 진행_중_작업_제약이_아닌_위반은_진행_중으로_바꾸지_않는다() throws Exception {
        when(announcements.existsById(7L)).thenReturn(true);
        when(inputs.read(7L)).thenReturn(INPUT);
        when(jobs.saveAndFlush(any())).thenThrow(violation("ai_jobs_announcement_id_fkey"));

        assertThatThrownBy(() -> service.request(7L, "admin", "공고문.pdf", AnnouncementPdfDocumentTest.pdf("cover")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 최근_결과는_성공한_작업에서_읽는다() {
        when(announcements.existsById(7L)).thenReturn(true);
        when(jobs.findLatestViews(PdfExtractionJob.KIND, 7L, PdfExtractionJobStatus.SUCCEEDED, Limit.of(1)))
                .thenReturn(List.of(new PdfExtractionJobView(31L, PdfExtractionJobStatus.SUCCEEDED, "공고문.pdf",
                        "ab".repeat(32), 1, NOW, NOW.plusSeconds(60), null, null, RESULT)));

        var latest = service.latest(7L);

        assertThat(latest).isNotNull();
        assertThat(latest.createdAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(latest.extraction().totalPages()).isEqualTo(9);
    }

    private static DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("violation", new SQLException("violation", "23505"), constraint));
    }

    @Test
    void 읽을_수_없는_PDF는_작업으로_넣지_않는다() {
        when(announcements.existsById(7L)).thenReturn(true);

        assertThatThrownBy(() -> service.request(7L, "admin", "공고문.pdf", new byte[] {1, 2}))
                .isInstanceOf(InvalidAnnouncementPdfException.class);
        verify(jobs, never()).saveAndFlush(any());
    }

    @Test
    void 없는_공고면_작업을_넣지_않는다() {
        when(announcements.existsById(7L)).thenReturn(false);

        assertThatThrownBy(() -> service.request(7L, "admin", "공고문.pdf", new byte[] {1}))
                .isInstanceOf(AnnouncementNotFoundException.class);
        verify(jobs, never()).saveAndFlush(any());
    }
}
