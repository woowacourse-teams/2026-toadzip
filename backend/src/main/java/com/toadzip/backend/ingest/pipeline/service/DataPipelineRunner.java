package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * {@link DataPipelineType#steps()}에 정의된 순서로 수집·정제 서비스를 실행한다.
 * 단계마다 부를 서비스는 {@link DataPipelineStepExecutor}가 정하고, 여기서는 건너뛰기·호출 제한·단계 상태 기록을 맡는다.
 * 실행 잠금·중단·복구는 {@link DataPipelineExecutionService}가 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataPipelineRunner {

    private static final String RATE_LIMIT_SKIP_REASON =
            "외부 API 호출 제한에 도달해 이 단계를 건너뛰었습니다.";

    private final DataPipelineStepExecutor stepExecutor;
    private final DataPipelineExecutionStateService executionStateService;
    private final MeterRegistry meterRegistry;

    public void run(DataPipelineType type, UUID executionId) {
        DataPipelinePartialFailureException firstReportedPartialFailure = null;
        DataPipelineStep previousPartiallyFailedStep = null;
        boolean lhRateLimited = false;
        boolean collectionRateLimited = false;
        for (DataPipelineStep step : type.steps()) {
            if (type.requiresSuccessfulCollection(step) && firstReportedPartialFailure != null
                    && firstReportedPartialFailure.getStep().isCollection()) {
                throw firstReportedPartialFailure;
            }
            StepOutcome outcome = runStep(
                    executionId, step, previousPartiallyFailedStep,
                    skipReason(type, step, collectionRateLimited, lhRateLimited)
            );
            lhRateLimited |= outcome.rateLimited() && isLhAnnouncementCollection(step);
            collectionRateLimited |= outcome.rateLimited() && step.isCollection();
            previousPartiallyFailedStep = null;
            if (outcome.partialFailure() == null) {
                continue;
            }
            previousPartiallyFailedStep = step;
            if (firstReportedPartialFailure == null) {
                firstReportedPartialFailure = new DataPipelinePartialFailureException(
                        step, outcome.partialFailure().serverResponse()
                );
            }
        }
        if (firstReportedPartialFailure != null) {
            throw firstReportedPartialFailure;
        }
    }

    private StepOutcome runStep(
            UUID executionId,
            DataPipelineStep step,
            DataPipelineStep previousPartiallyFailedStep,
            String skipReason
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "failed";
        try {
            startStep(executionId, step, previousPartiallyFailedStep);
            if (skipReason != null) {
                executionStateService.skipStep(
                        executionId, step, skipReason, "{}"
                );
                outcome = "skipped";
                if (isLhAnnouncementCollection(step)) {
                    outcome = "rate_limited";
                }
                return new StepOutcome(false, null);
            }
            DataPipelineStepResult result = stepExecutor.execute(step);
            if (result.failedOnlyByRateLimit()) {
                executionStateService.skipStep(executionId, step, RATE_LIMIT_SKIP_REASON, result.serverResponse());
                outcome = "rate_limited";
                return new StepOutcome(true, null);
            }
            if (result.failed()) {
                executionStateService.recordPartialFailure(executionId, step, result.serverResponse());
                return new StepOutcome(
                        result.rateLimitedFailureCount() > 0, result
                );
            }
            if (result.hasWarnings()) {
                executionStateService.completeStepWithWarnings(executionId, step, result.serverResponse());
                outcome = "completed_with_warnings";
                return new StepOutcome(false, null);
            }
            executionStateService.completeStep(executionId, step, result.serverResponse());
            outcome = "completed";
            return new StepOutcome(false, null);
        }
        finally {
            long durationNanos = sample.stop(meterRegistry.timer(
                    "ingest.pipeline.step", "step", step.name(), "result", outcome
            ));
            log.info("event=ingest.pipeline.step.finished executionId={} step={} result={} durationMs={}",
                    MDC.get("executionId"), step, outcome, durationNanos / 1_000_000);
        }
    }

    private String skipReason(
            DataPipelineType type, DataPipelineStep step, boolean collectionRateLimited, boolean lhRateLimited
    ) {
        if (type.requiresSuccessfulCollection(step) && collectionRateLimited) {
            return "수집이 호출 제한으로 완료되지 않아 자동 정제를 실행하지 않았습니다. "
                    + "수집 결과를 확인한 뒤 저장된 원천으로 정제를 실행할 수 있습니다.";
        }
        if (lhRateLimited && isLhAnnouncementCollection(step)) {
            return "앞선 LH 공고 단계의 호출 제한으로 이 단계를 건너뛰었습니다.";
        }
        return null;
    }

    private void startStep(UUID executionId, DataPipelineStep step, DataPipelineStep previousPartiallyFailedStep) {
        IngestExecutionScope.verifyCanContinue();
        if (previousPartiallyFailedStep != null) {
            executionStateService.startStepAfterPartialFailure(executionId, previousPartiallyFailedStep, step);
            return;
        }
        executionStateService.startStep(executionId, step);
    }

    private boolean isLhAnnouncementCollection(DataPipelineStep step) {
        return step == DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_CATALOG
                || step == DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_SUPPLIES
                || step == DataPipelineStep.COLLECT_LH_ANNOUNCEMENT_DETAILS;
    }

    private record StepOutcome(boolean rateLimited, DataPipelineStepResult partialFailure) {
    }
}
