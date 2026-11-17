package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.PdfExtractionJob;
import com.toadzip.backend.announcement.domain.PdfExtractionQueue;
import com.toadzip.backend.announcement.repository.PdfExtractionJobRepository;
import com.toadzip.backend.announcement.repository.PdfExtractionJobRepository.QueueSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PdfExtractionQueueServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-16T09:00:00Z");

    private final PdfExtractionJobRepository jobs = mock(PdfExtractionJobRepository.class);
    private final QueueSummary summary = mock(QueueSummary.class);
    private final PdfExtractionQueueService service =
            new PdfExtractionQueueService(jobs, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 시작까지의_추정_시간은_앞선_작업_수에_최근_평균_처리_시간을_곱한다() {
        when(jobs.countAhead(31L, NOW, NOW.minus(Duration.ofMinutes(30)), 3)).thenReturn(3L);
        when(jobs.summarizeQueue(PdfExtractionJob.KIND, NOW.minus(Duration.ofHours(24)))).thenReturn(summary);
        when(summary.getAverageProcessingSeconds()).thenReturn(26.5);

        assertThat(service.position(31L)).isEqualTo(new PdfExtractionQueue.Position(3, Duration.ofMillis(79_500)));
    }

    @Test
    void 최근에_성공한_작업이_없으면_추정_시간을_비운다() {
        when(jobs.countAhead(31L, NOW, NOW.minus(Duration.ofMinutes(30)), 3)).thenReturn(2L);
        when(jobs.summarizeQueue(any(), any())).thenReturn(summary);
        when(summary.getAverageProcessingSeconds()).thenReturn(null);

        assertThat(service.position(31L)).isEqualTo(new PdfExtractionQueue.Position(2, null));
    }
}
