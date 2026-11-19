package com.toadzip.backend.ingest.pipeline.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 자동 실행이 지금 시작할 유형과 수집 범위를 정한다.
 * <ul>
 *     <li>마지막 성공에서 실행 간격이 지난 유형만 시작한다. 쉬는 동안 놓친 실행은 한 번으로 따라잡는다.</li>
 *     <li>마지막 성공 뒤 성공하지 못한 실행이 이어지면 마지막 실행에서 10분, 30분, 60분을 차례로 기다린다.</li>
 *     <li>여러 유형이 기다리면 가장 오래 성공하지 못한 유형 하나를 시작한다. 실행 잠금은 하나라서 한 번에 하나만 돈다.</li>
 *     <li>LH 공고 갱신은 전체 수집 간격 안에 전체 범위로 성공했으면 증분으로, 아니면 전체로 받는다.</li>
 * </ul>
 */
public final class AutoCollectionPolicy {

    private static final List<Duration> FAILURE_BACKOFF = List.of(
            Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofMinutes(60)
    );

    private final Duration interval;
    private final Duration fullCollectionInterval;

    public AutoCollectionPolicy(Duration interval, Duration fullCollectionInterval) {
        requirePositive(interval);
        requirePositive(fullCollectionInterval);
        this.interval = interval;
        this.fullCollectionInterval = fullCollectionInterval;
    }

    public Optional<Plan> next(Instant now, List<AutoCollectionRecord> records) {
        return records.stream()
                .filter(record -> isDue(now, record))
                .min(Comparator.comparing(AutoCollectionRecord::lastSuccessAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(record -> new Plan(record.type(), scopeOf(now, record)));
    }

    private boolean isDue(Instant now, AutoCollectionRecord record) {
        if (record.lastSuccessAt() != null && now.isBefore(record.lastSuccessAt().plus(interval))) {
            return false;
        }
        if (record.failureStreak() == 0) {
            return true;
        }
        Duration backoff = FAILURE_BACKOFF.get(Math.min(record.failureStreak(), FAILURE_BACKOFF.size()) - 1);
        return !now.isBefore(record.lastAttemptAt().plus(backoff));
    }

    private DataPipelineCollectionScope scopeOf(Instant now, AutoCollectionRecord record) {
        if (record.type() != DataPipelineType.ANNOUNCEMENT_SYNC) {
            return null;
        }
        Instant lastFull = record.lastFullSuccessAt();
        if (lastFull != null && now.isBefore(lastFull.plus(fullCollectionInterval))) {
            return DataPipelineCollectionScope.INCREMENTAL;
        }
        return DataPipelineCollectionScope.FULL;
    }

    private static void requirePositive(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("자동 실행 간격은 양수여야 합니다.");
        }
    }

    /** @param collectionScope 수집 범위. 범위를 나눠 받지 않는 유형은 null이다. */
    public record Plan(DataPipelineType type, DataPipelineCollectionScope collectionScope) {
    }
}
