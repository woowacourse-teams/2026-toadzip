package com.toadzip.backend.ingest.collection.dto;

import com.toadzip.backend.ingest.domain.IngestStepReport;

public record ExternalDataCollectionReport(
        String operation,
        int storedRowCount,
        int failedRequestCount,
        int externalApiCallCount,
        int skippedRequestCount,
        int rateLimitedRequestCount,
        int successfulRequestCount,
        int selectionFailedRequestCount
) implements IngestStepReport {

    public ExternalDataCollectionReport(String operation, int storedRowCount, int failedRequestCount,
            int externalApiCallCount, int skippedRequestCount, int rateLimitedRequestCount,
            int successfulRequestCount) {
        this(operation, storedRowCount, failedRequestCount, externalApiCallCount, skippedRequestCount,
                rateLimitedRequestCount, successfulRequestCount, 0);
    }

    public ExternalDataCollectionReport(String operation, int storedRowCount, int failedRequestCount,
            int externalApiCallCount, int skippedRequestCount, int rateLimitedRequestCount) {
        this(operation, storedRowCount, failedRequestCount, externalApiCallCount, skippedRequestCount,
                rateLimitedRequestCount, 0);
    }

    public ExternalDataCollectionReport(
            String operation,
            int storedRowCount,
            int failedRequestCount,
            int externalApiCallCount
    ) {
        this(operation, storedRowCount, failedRequestCount, externalApiCallCount, 0, 0);
    }

    public ExternalDataCollectionReport(
            String operation,
            int storedRowCount,
            int failedRequestCount,
            int externalApiCallCount,
            int skippedRequestCount
    ) {
        this(operation, storedRowCount, failedRequestCount, externalApiCallCount, skippedRequestCount, 0);
    }

    public ExternalDataCollectionReport {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("수집 작업명은 필수입니다.");
        }
        if (storedRowCount < 0 || failedRequestCount < 0 || externalApiCallCount < 0
                || skippedRequestCount < 0 || rateLimitedRequestCount < 0
                || rateLimitedRequestCount > failedRequestCount || successfulRequestCount < 0
                || selectionFailedRequestCount < 0 || selectionFailedRequestCount > failedRequestCount
                || rateLimitedRequestCount + selectionFailedRequestCount > failedRequestCount) {
            throw new IllegalArgumentException("수집 결과 개수는 음수일 수 없습니다.");
        }
    }

    public static ExternalDataCollectionReport empty(String operation) {
        return new ExternalDataCollectionReport(operation, 0, 0, 0);
    }

    public ExternalDataCollectionReport plus(ExternalDataCollectionReport other) {
        if (!operation.equals(other.operation)) {
            throw new IllegalArgumentException("서로 다른 수집 작업은 합칠 수 없습니다.");
        }
        return new ExternalDataCollectionReport(
                operation,
                storedRowCount + other.storedRowCount,
                failedRequestCount + other.failedRequestCount,
                externalApiCallCount + other.externalApiCallCount,
                skippedRequestCount + other.skippedRequestCount,
                rateLimitedRequestCount + other.rateLimitedRequestCount,
                successfulRequestCount + other.successfulRequestCount,
                selectionFailedRequestCount + other.selectionFailedRequestCount
        );
    }

    @Override
    public int stepFailureCount() {
        return failedRequestCount;
    }

    @Override
    public int stepRateLimitedFailureCount() {
        return rateLimitedRequestCount;
    }

    /** 실패한 요청이 있고 저장한 행이 없다. 성공 요청 수는 모든 수집기가 세지 않으므로 저장한 행 수로 본다. */
    @Override
    public boolean stepFailedEntirely() {
        return failedRequestCount > 0 && storedRowCount == 0;
    }
}
