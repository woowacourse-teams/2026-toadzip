package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.global.config.SchedulingConfig;
import com.toadzip.backend.ingest.collection.configuration.ExternalDataIngestProperties;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.pipeline.domain.AutoCollectionPolicy;
import com.toadzip.backend.ingest.pipeline.domain.AutoCollectionRecord;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * LH·SH·GH 공고 수집을 정해진 간격으로 스스로 시작한다. 판단은 {@link AutoCollectionPolicy}가 하고, 이 클래스는 최근 실행을 읽어
 * 넘기고 고른 실행을 시작한다. 관리자 실행이나 다른 서버의 실행이 잠금을 잡고 있으면 이번 확인은 건너뛴다.
 * 확인할 때마다 유형별 마지막 성공 시각과 연속 실패 수를 지표로 내보내 경보가 읽게 한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ingest.auto-collection.enabled", havingValue = "true")
public class AnnouncementAutoCollectionScheduler {

    static final List<DataPipelineType> TYPES = List.of(
            DataPipelineType.ANNOUNCEMENT_SYNC,
            DataPipelineType.SH_ANNOUNCEMENT_COLLECTION,
            DataPipelineType.GH_ANNOUNCEMENT_COLLECTION
    );

    private final DataPipelineExecutionService executions;
    private final DataPipelineExecutionRepository history;
    private final ExternalDataIngestProperties ingestProperties;
    private final Clock clock;
    private final AutoCollectionPolicy policy;
    private final Map<DataPipelineType, AutoCollectionRecord> lastRecords = new EnumMap<>(DataPipelineType.class);

    public AnnouncementAutoCollectionScheduler(
            DataPipelineExecutionService executions,
            DataPipelineExecutionRepository history,
            ExternalDataIngestProperties ingestProperties,
            Clock clock,
            MeterRegistry registry,
            @Value("${ingest.auto-collection.interval}") Duration interval,
            @Value("${ingest.auto-collection.full-collection-interval}") Duration fullCollectionInterval
    ) {
        this.executions = executions;
        this.history = history;
        this.ingestProperties = ingestProperties;
        this.clock = clock;
        this.policy = new AutoCollectionPolicy(interval, fullCollectionInterval);
        TYPES.forEach(type -> registerGauges(registry, type));
    }

    @Scheduled(
            fixedDelayString = "${ingest.auto-collection.check-interval}",
            initialDelayString = "${ingest.auto-collection.initial-delay}",
            scheduler = SchedulingConfig.AUTO_RUN_SCHEDULER
    )
    public void startDue() {
        Instant now = clock.instant();
        List<AutoCollectionRecord> records = TYPES.stream().map(this::record).toList();
        synchronized (lastRecords) {
            records.forEach(record -> lastRecords.put(record.type(), record));
        }
        List<AutoCollectionRecord> startable = records.stream()
                .filter(record -> !record.type().requiresServiceKey() || ingestProperties.hasServiceKey())
                .toList();
        policy.next(now, startable).ifPresent(plan -> start(plan, now));
    }

    private AutoCollectionRecord record(DataPipelineType type) {
        List<AutoCollectionRecord.Run> recent = history.findTop20ByTypeOrderByIdDesc(type).stream()
                .map(run -> new AutoCollectionRecord.Run(run.getStatus(), run.getFailureCause(), run.getStartedAt()))
                .toList();
        Instant lastFull = history.findLastFullSuccessStartedAt(type).orElse(null);
        return AutoCollectionRecord.of(type, recent, lastFull);
    }

    private void start(AutoCollectionPolicy.Plan plan, Instant scheduledAt) {
        try {
            var accepted = executions.startScheduled(plan.type(), scheduledAt, plan.collectionScope());
            log.info("event=ingest.auto.started type={} scope={} executionId={}",
                    plan.type(), plan.collectionScope(), accepted.executionId());
        }
        catch (IngestAlreadyRunningException exception) {
            log.info("event=ingest.auto.skipped type={} reason=already_running", plan.type());
        }
    }

    private void registerGauges(MeterRegistry registry, DataPipelineType type) {
        Gauge.builder("toadzip.ingest.auto.last.success", this, scheduler -> scheduler.lastSuccessEpochSeconds(type))
                .description("자동 실행 유형의 마지막 성공 시작 시각(유닉스 초). 성공한 적이 없으면 비어 있다.")
                .baseUnit("seconds")
                .tag("type", type.name())
                .register(registry);
        Gauge.builder("toadzip.ingest.auto.failure.streak", this, scheduler -> scheduler.failureStreak(type))
                .description("자동 실행 유형의 마지막 성공 뒤 이어진 실패 수")
                .tag("type", type.name())
                .register(registry);
    }

    private double lastSuccessEpochSeconds(DataPipelineType type) {
        AutoCollectionRecord record = lastRecord(type);
        if (record == null || record.lastSuccessAt() == null) {
            return Double.NaN;
        }
        return record.lastSuccessAt().getEpochSecond();
    }

    private double failureStreak(DataPipelineType type) {
        AutoCollectionRecord record = lastRecord(type);
        if (record == null) {
            return Double.NaN;
        }
        return record.failureStreak();
    }

    private AutoCollectionRecord lastRecord(DataPipelineType type) {
        synchronized (lastRecords) {
            return lastRecords.get(type);
        }
    }
}
