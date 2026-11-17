package com.toadzip.backend.ingest.pipeline.domain;

import java.time.Instant;
import java.util.List;

/**
 * 한 실행 유형의 최근 실행 중 자동 실행이 볼 것만 모은다. 관리자 실행도 함께 센다.
 *
 * @param lastSuccessAt 마지막으로 성공한 실행의 시작 시각({@link Run#succeeded()}). 성공한 적이 없으면 null이다.
 * @param failureStreak 마지막 성공 뒤 성공하지 못하고 끝난 실행 수(실패, 호출 제한으로 건너뜀, 중지)
 * @param lastAttemptAt 마지막으로 끝난 실행의 시작 시각. 끝난 실행이 없으면 null이다.
 * @param lastFullSuccessAt 전체 범위로 성공한 마지막 실행의 시작 시각. 관리자 실행은 전체 범위다.
 */
public record AutoCollectionRecord(
        DataPipelineType type,
        Instant lastSuccessAt,
        int failureStreak,
        Instant lastAttemptAt,
        Instant lastFullSuccessAt
) {

    /** @param recent 최근 실행. 새 실행부터 오래된 실행 순서다. 실행 중인 실행은 건너뛴다. */
    public static AutoCollectionRecord of(DataPipelineType type, List<Run> recent, Instant lastFullSuccessAt) {
        Instant lastSuccessAt = null;
        Instant lastAttemptAt = null;
        int failureStreak = 0;
        for (Run run : recent) {
            if (!run.status().isFinished()) {
                continue;
            }
            if (lastAttemptAt == null) {
                lastAttemptAt = run.startedAt();
            }
            if (run.succeeded()) {
                lastSuccessAt = run.startedAt();
                break;
            }
            failureStreak++;
        }
        return new AutoCollectionRecord(type, lastSuccessAt, failureStreak, lastAttemptAt, lastFullSuccessAt);
    }

    /** @param failureCause 실패로 끝난 실행의 원인. 실패하지 않았으면 null이다. */
    public record Run(DataPipelineExecutionStatus status, DataPipelineFailureCause failureCause, Instant startedAt) {

        /**
         * 자동 실행이 성공으로 세는 실행이다. 대상 일부만 실패한 실행(PARTIAL)은 나머지를 처리했고 대상별 실패는 실패 목록에 남으므로
         * 성공으로 센다. 원천 하나가 계속 거절되어도 경보와 전체 수집 시점이 그 하나에 묶이지 않게 한다.
         * DataPipelineExecutionRepository.findLastFullSuccessStartedAt의 조건과 같아야 한다.
         */
        boolean succeeded() {
            return status.isSuccessful()
                    || status == DataPipelineExecutionStatus.FAILED && failureCause == DataPipelineFailureCause.PARTIAL;
        }
    }
}
