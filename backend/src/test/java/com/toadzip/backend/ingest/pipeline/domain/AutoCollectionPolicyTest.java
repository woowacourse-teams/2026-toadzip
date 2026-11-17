package com.toadzip.backend.ingest.pipeline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.pipeline.domain.AutoCollectionRecord.Run;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AutoCollectionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private final AutoCollectionPolicy policy = new AutoCollectionPolicy(Duration.ofHours(1), Duration.ofDays(7));

    @Test
    void 마지막_성공에서_실행_간격이_지나야_시작하고_오래_쉬었어도_한_번만_시작한다() {
        var recent = record(DataPipelineType.SH_ANNOUNCEMENT_COLLECTION, List.of(success(minutes(59))));
        var overdue = record(DataPipelineType.SH_ANNOUNCEMENT_COLLECTION, List.of(success(Duration.ofDays(2))));

        assertThat(policy.next(NOW, List.of(recent))).isEmpty();
        assertThat(policy.next(NOW, List.of(overdue)))
                .contains(new AutoCollectionPolicy.Plan(DataPipelineType.SH_ANNOUNCEMENT_COLLECTION, null));
    }

    @Test
    void 성공한_적이_없는_유형은_바로_시작한다() {
        assertThat(policy.next(NOW, List.of(record(DataPipelineType.GH_ANNOUNCEMENT_COLLECTION, List.of()))))
                .map(AutoCollectionPolicy.Plan::type).contains(DataPipelineType.GH_ANNOUNCEMENT_COLLECTION);
    }

    @ParameterizedTest
    @CsvSource({"1, 9, false", "1, 10, true", "2, 29, false", "2, 30, true", "3, 59, false", "3, 60, true",
            "7, 59, false", "7, 60, true"})
    void 연속_실패는_마지막_실행에서_10분_30분_60분을_차례로_기다린다(int failures, int minutesSinceLastFailure,
            boolean due) {
        List<Run> runs = new ArrayList<>();
        for (int index = 0; index < failures; index++) {
            runs.add(new Run(DataPipelineExecutionStatus.FAILED, NOW.minus(minutes(minutesSinceLastFailure + index))));
        }
        runs.add(success(Duration.ofHours(5)));

        var record = record(DataPipelineType.SH_ANNOUNCEMENT_COLLECTION, runs);

        assertThat(record.failureStreak()).isEqualTo(failures);
        assertThat(policy.next(NOW, List.of(record)).isPresent()).isEqualTo(due);
    }

    @Test
    void 호출_제한으로_건너뛴_완료와_중지도_실패로_세고_실행_중인_실행은_세지_않는다() {
        var record = record(DataPipelineType.ANNOUNCEMENT_SYNC, List.of(
                new Run(DataPipelineExecutionStatus.RUNNING, NOW.minus(minutes(1))),
                new Run(DataPipelineExecutionStatus.COMPLETED_WITH_SKIPS, NOW.minus(minutes(20))),
                new Run(DataPipelineExecutionStatus.STOPPED, NOW.minus(minutes(40))),
                new Run(DataPipelineExecutionStatus.COMPLETED_WARNINGS, NOW.minus(Duration.ofHours(3)))));

        assertThat(record.failureStreak()).isEqualTo(2);
        assertThat(record.lastAttemptAt()).isEqualTo(NOW.minus(minutes(20)));
        assertThat(record.lastSuccessAt()).isEqualTo(NOW.minus(Duration.ofHours(3)));
        // 두 번 실패했으므로 마지막 실행에서 30분을 기다린다.
        assertThat(policy.next(NOW, List.of(record))).isEmpty();
    }

    @Test
    void 관리자_실행이_실패한_직후라도_마지막_성공에서_간격이_지나지_않았으면_시작하지_않는다() {
        var record = record(DataPipelineType.SH_ANNOUNCEMENT_COLLECTION, List.of(
                new Run(DataPipelineExecutionStatus.FAILED, NOW.minus(minutes(40))), success(minutes(50))));

        assertThat(policy.next(NOW, List.of(record))).isEmpty();
    }

    @Test
    void 여러_유형이_기다리면_가장_오래_성공하지_못한_유형부터_시작한다() {
        var sh = record(DataPipelineType.SH_ANNOUNCEMENT_COLLECTION, List.of(success(Duration.ofHours(2))));
        var gh = record(DataPipelineType.GH_ANNOUNCEMENT_COLLECTION, List.of(success(Duration.ofHours(5))));
        var lh = record(DataPipelineType.ANNOUNCEMENT_SYNC, List.of(success(Duration.ofHours(3))));

        assertThat(policy.next(NOW, List.of(sh, lh, gh)))
                .map(AutoCollectionPolicy.Plan::type).contains(DataPipelineType.GH_ANNOUNCEMENT_COLLECTION);
    }

    @Test
    void LH는_전체_수집_간격_안에_전체로_성공했으면_증분으로_아니면_전체로_받는다() {
        List<Run> runs = List.of(success(Duration.ofHours(2)));
        var recentFull = new AutoCollectionRecord(DataPipelineType.ANNOUNCEMENT_SYNC, NOW.minus(Duration.ofHours(2)),
                0, NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofDays(6)));
        var oldFull = AutoCollectionRecord.of(DataPipelineType.ANNOUNCEMENT_SYNC, runs, NOW.minus(Duration.ofDays(7)));
        var neverFull = AutoCollectionRecord.of(DataPipelineType.ANNOUNCEMENT_SYNC, runs, null);

        assertThat(policy.next(NOW, List.of(recentFull))).map(AutoCollectionPolicy.Plan::collectionScope)
                .contains(DataPipelineCollectionScope.INCREMENTAL);
        assertThat(policy.next(NOW, List.of(oldFull))).map(AutoCollectionPolicy.Plan::collectionScope)
                .contains(DataPipelineCollectionScope.FULL);
        assertThat(policy.next(NOW, List.of(neverFull))).map(AutoCollectionPolicy.Plan::collectionScope)
                .contains(DataPipelineCollectionScope.FULL);
    }

    @Test
    void 실행_간격은_양수여야_한다() {
        assertThatThrownBy(() -> new AutoCollectionPolicy(Duration.ZERO, Duration.ofDays(7)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutoCollectionPolicy(Duration.ofHours(1), Duration.ofHours(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AutoCollectionRecord record(DataPipelineType type, List<Run> runs) {
        return AutoCollectionRecord.of(type, runs, null);
    }

    private Run success(Duration ago) {
        return new Run(DataPipelineExecutionStatus.COMPLETED, NOW.minus(ago));
    }

    private Duration minutes(long minutes) {
        return Duration.ofMinutes(minutes);
    }
}
