package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineType;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 실행을 실패로 끝낸다. 실패를 처리하던 중이라 상태 저장이나 현재 단계 조회가 다시 실패해도 예외를 던지지 않고 로그만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataPipelineFailureRecorder {

    private final DataPipelineExecutionStateService executionStateService;
    private final Clock clock;

    void record(
            UUID executionId,
            DataPipelineType type,
            DataPipelineStep failedStep,
            String message,
            String serverResponse
    ) {
        try {
            executionStateService.fail(
                    executionId,
                    failedStep,
                    message,
                    serverResponse,
                    Instant.now(clock)
            );
        }
        catch (RuntimeException exception) {
            log.error(
                    "데이터 수집·정제 실패 상태를 저장하지 못했습니다: type={}, step={}",
                    type,
                    failedStep,
                    exception
            );
        }
    }

    /** 실패한 단계를 모르면 null이다. */
    DataPipelineStep currentStep(UUID executionId) {
        try {
            return executionStateService.findCurrentStep(executionId);
        }
        catch (RuntimeException exception) {
            log.error(
                    "데이터 수집·정제 현재 단계를 조회하지 못했습니다: executionId={}",
                    executionId,
                    exception
            );
            return null;
        }
    }

    void recordAtCurrentStep(UUID executionId, DataPipelineType type, String message) {
        record(executionId, type, currentStep(executionId), message, null);
    }
}
