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
    /** ai-worker worker.MAX_ATTEMPTS와 같다. */
    static final int MAX_ATTEMPTS = 3;
    /** ai-worker worker.CLAIM_WINDOW(마감 40분 - 한 번의 추출 한도 10분)와 같다. 넣은 지 이보다 오래된 작업은 꺼내지 않는다. */
    static final Duration CLAIM_WINDOW = Duration.ofMinutes(30);

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

    /**
     * 기다리는 작업 앞에 몇 건이 있고 시작까지 얼마나 걸릴지 추정한다. 워커 하나가 한 번에 하나씩 처리하므로 앞선 작업 수에
     * 최근 평균 처리 시간을 곱한다. 최근에 성공한 작업이 없으면 추정 시간은 null이다.
     */
    public PdfExtractionQueue.Position position(long jobId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long ahead = jobs.countAhead(jobId, now, now.minus(CLAIM_WINDOW), MAX_ATTEMPTS);
        Duration average = current().averageProcessing();
        if (average == null) {
            return new PdfExtractionQueue.Position(ahead, null);
        }
        return new PdfExtractionQueue.Position(ahead, average.multipliedBy(ahead));
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
