package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.PdfExtractionQueue;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 추출 작업 큐 요약을 Prometheus gauge로 내보낸다. 수집할 때마다 DB를 읽지 않게 요약을 잠깐 재사용한다.
 * 요약을 읽지 못하면 이전 값을 보이지 않고 NaN으로 비워, 대시보드가 멈춘 값을 정상 값으로 보이지 않게 한다.
 */
@Slf4j
@Component
public class PdfExtractionQueueMetrics {
    static final Duration REUSE = Duration.ofSeconds(10);

    private final PdfExtractionQueueService queue;
    private final Clock clock;
    private PdfExtractionQueue cached;
    private Instant cachedAt;

    public PdfExtractionQueueMetrics(PdfExtractionQueueService queue, Clock clock, MeterRegistry registry) {
        this.queue = queue;
        this.clock = clock;
        jobs(registry, "pending", PdfExtractionQueue::pending);
        jobs(registry, "retrying", PdfExtractionQueue::retrying);
        jobs(registry, "running", PdfExtractionQueue::running);
        recent(registry, "succeeded", PdfExtractionQueue::succeeded);
        recent(registry, "failed", PdfExtractionQueue::failed);
        seconds(registry, "toadzip.ai.jobs.oldest.wait", null, PdfExtractionQueue::oldestWait);
        seconds(registry, "toadzip.ai.jobs.processing", "average", PdfExtractionQueue::averageProcessing);
        seconds(registry, "toadzip.ai.jobs.processing", "max", PdfExtractionQueue::maxProcessing);
    }

    private void jobs(MeterRegistry registry, String status, Function<PdfExtractionQueue, Long> count) {
        Gauge.builder("toadzip.ai.jobs", this, metrics -> metrics.count(count))
                .description("진행 중인 공고문 PDF 추출 작업 수")
                .tag("status", status)
                .register(registry);
    }

    private void recent(MeterRegistry registry, String result, Function<PdfExtractionQueue, Long> count) {
        Gauge.builder("toadzip.ai.jobs.recent", this, metrics -> metrics.count(count))
                .description("최근 24시간 안에 끝난 공고문 PDF 추출 작업 수")
                .tag("result", result)
                .register(registry);
    }

    private void seconds(MeterRegistry registry, String name, String stat, Function<PdfExtractionQueue, Duration> value) {
        Gauge.Builder<PdfExtractionQueueMetrics> builder = Gauge.builder(name, this, metrics -> metrics.seconds(value))
                .baseUnit("seconds");
        if (stat != null) {
            builder.tag("stat", stat);
        }
        builder.register(registry);
    }

    private double count(Function<PdfExtractionQueue, Long> count) {
        PdfExtractionQueue snapshot = snapshot();
        if (snapshot == null) {
            return Double.NaN;
        }
        return count.apply(snapshot);
    }

    private double seconds(Function<PdfExtractionQueue, Duration> value) {
        PdfExtractionQueue snapshot = snapshot();
        if (snapshot == null) {
            return Double.NaN;
        }
        Duration duration = value.apply(snapshot);
        if (duration == null) {
            return 0;
        }
        return duration.toMillis() / 1000.0;
    }

    private synchronized PdfExtractionQueue snapshot() {
        Instant now = clock.instant();
        if (cachedAt != null && now.isBefore(cachedAt.plus(REUSE))) {
            return cached;
        }
        cachedAt = now;
        try {
            cached = queue.current();
        }
        catch (RuntimeException exception) {
            log.warn("event=announcement.pdf_extraction.queue_metrics_failed reason={}", exception.toString());
            cached = null;
        }
        return cached;
    }
}
