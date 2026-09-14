package com.toadzip.backend.ingest.pipeline.service;

record DataPipelineStepResult(
        String serverResponse,
        int failureCount,
        int rateLimitedFailureCount
) {

    DataPipelineStepResult {
        if (failureCount < 0
                || rateLimitedFailureCount < 0
                || rateLimitedFailureCount > failureCount) {
            throw new IllegalArgumentException("파이프라인 단계 실패 개수가 올바르지 않습니다.");
        }
    }

    boolean failed() {
        return failureCount > 0;
    }

    boolean failedOnlyByRateLimit() {
        return failed() && failureCount == rateLimitedFailureCount;
    }
}
