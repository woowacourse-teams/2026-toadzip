package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.PdfExtractionQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PdfExtractionQueueMetricsTest {
    private static final Instant NOW = Instant.parse("2026-11-16T09:00:00Z");

    private final PdfExtractionQueueService queue = mock(PdfExtractionQueueService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void 큐_요약을_상태별_수와_초_단위_대기_처리_시간으로_내보낸다() {
        when(queue.current()).thenReturn(new PdfExtractionQueue(3, 1, 1, Duration.ofMinutes(12), 20, 2,
                Duration.ofMillis(26_500), Duration.ofSeconds(90), Duration.ofHours(24), NOW));
        new PdfExtractionQueueMetrics(queue, Clock.fixed(NOW, ZoneOffset.UTC), registry);

        assertThat(gauge("toadzip.ai.jobs", "status", "pending")).isEqualTo(3);
        assertThat(gauge("toadzip.ai.jobs", "status", "retrying")).isEqualTo(1);
        assertThat(gauge("toadzip.ai.jobs", "status", "running")).isEqualTo(1);
        assertThat(gauge("toadzip.ai.jobs.recent", "result", "failed")).isEqualTo(2);
        assertThat(registry.get("toadzip.ai.jobs.oldest.wait").gauge().value()).isEqualTo(720);
        assertThat(gauge("toadzip.ai.jobs.processing", "stat", "average")).isEqualTo(26.5);
        // 수집마다 DB를 읽지 않는다.
        verify(queue, times(1)).current();
    }

    @Test
    void 대기가_없으면_가장_오래된_대기는_0초다() {
        when(queue.current()).thenReturn(new PdfExtractionQueue(0, 0, 0, null, 0, 0, null, null,
                Duration.ofHours(24), NOW));
        new PdfExtractionQueueMetrics(queue, Clock.fixed(NOW, ZoneOffset.UTC), registry);

        assertThat(registry.get("toadzip.ai.jobs.oldest.wait").gauge().value()).isZero();
    }

    @Test
    void 요약을_읽지_못하면_이전_값_대신_NaN을_보인다() {
        MutableClock clock = new MutableClock(NOW);
        when(queue.current())
                .thenReturn(new PdfExtractionQueue(5, 0, 1, Duration.ofMinutes(1), 0, 0, null, null,
                        Duration.ofHours(24), NOW))
                .thenThrow(new IllegalStateException("DB 연결 실패"));
        new PdfExtractionQueueMetrics(queue, clock, registry);
        assertThat(gauge("toadzip.ai.jobs", "status", "pending")).isEqualTo(5);

        clock.now = NOW.plus(PdfExtractionQueueMetrics.REUSE);

        assertThat(gauge("toadzip.ai.jobs", "status", "pending")).isNaN();
    }

    @Test
    void Grafana_대시보드가_읽는_시계열_이름을_Prometheus_수집_결과가_모두_내보낸다() throws IOException {
        when(queue.current()).thenReturn(new PdfExtractionQueue(3, 1, 1, Duration.ofMinutes(12), 20, 2,
                Duration.ofMillis(26_500), Duration.ofSeconds(90), Duration.ofHours(24), NOW));
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new PdfExtractionQueueMetrics(queue, Clock.fixed(NOW, ZoneOffset.UTC), prometheus);
        Set<String> exported = new TreeSet<>();
        for (String line : prometheus.scrape().split("\n")) {
            if (!line.startsWith("#") && !line.isBlank()) {
                exported.add(line.split("[{ ]")[0]);
            }
        }

        Set<String> used = new TreeSet<>();
        Matcher names = Pattern.compile("toadzip_ai_jobs[a-z_]*")
                .matcher(Files.readString(Path.of("../infra/grafana/dashboards/ai-jobs.json")));
        while (names.find()) {
            used.add(names.group());
        }

        assertThat(used).isNotEmpty();
        assertThat(exported).containsAll(used);
    }

    private double gauge(String name, String tag, String value) {
        return registry.get(name).tag(tag, value).gauge().value();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
