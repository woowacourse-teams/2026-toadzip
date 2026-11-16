package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.PdfExtractionJob;
import com.toadzip.backend.announcement.domain.PdfExtractionQueue;
import com.toadzip.backend.announcement.repository.PdfExtractionJobRepository;
import com.toadzip.backend.announcement.repository.PdfExtractionJobRepository.QueueSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공고문 PDF 추출 작업 큐가 얼마나 밀렸는지 읽는다. 운영 홈과 메트릭이 같은 값을 쓴다. */
@Service
@Transactional(readOnly = true)
public class PdfExtractionQueueService {
    static final Duration RECENT = Duration.ofHours(24);

    private final PdfExtractionJobRepository jobs;
    private final Clock clock;

    public PdfExtractionQueueService(PdfExtractionJobRepository jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    public PdfExtractionQueue current() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        QueueSummary summary = jobs.summarizeQueue(PdfExtractionJob.KIND, now.minus(RECENT));
        return new PdfExtractionQueue(summary.getPending(), summary.getRetrying(), summary.getRunning(),
                since(summary.getOldestWaitingSince(), now), summary.getSucceeded(), summary.getFailed(),
                seconds(summary.getAverageProcessingSeconds()), seconds(summary.getMaxProcessingSeconds()), RECENT, now);
    }

    private static Duration since(Instant start, Instant now) {
        if (start == null) {
            return null;
        }
        return Duration.between(start, now);
    }

    private static Duration seconds(Double value) {
        if (value == null) {
            return null;
        }
        return Duration.ofMillis(Math.round(value * 1000));
    }
}
