package com.toadzip.backend.ingest.dto;

public record MyHomeComplexCollectionReport(
        String operation,
        int storedRowCount,
        int failedRequestCount,
        int externalApiCallCount,
        int rateLimitedRequestCount
) {

    public MyHomeComplexCollectionReport(
            String operation,
            int storedRowCount,
            int failedRequestCount,
            int externalApiCallCount
    ) {
        this(operation, storedRowCount, failedRequestCount, externalApiCallCount, 0);
    }

    public MyHomeComplexCollectionReport {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("수집 작업명은 필수입니다.");
        }
        if (storedRowCount < 0 || failedRequestCount < 0 || externalApiCallCount < 0
                || rateLimitedRequestCount < 0 || rateLimitedRequestCount > failedRequestCount) {
            throw new IllegalArgumentException("수집 결과 개수는 음수일 수 없습니다.");
        }
    }

    public static MyHomeComplexCollectionReport empty() {
        return new MyHomeComplexCollectionReport("myhome-complex", 0, 0, 0, 0);
    }

    public MyHomeComplexCollectionReport plus(MyHomeComplexCollectionReport other) {
        return new MyHomeComplexCollectionReport(
                operation,
                storedRowCount + other.storedRowCount,
                failedRequestCount + other.failedRequestCount,
                externalApiCallCount + other.externalApiCallCount,
                rateLimitedRequestCount + other.rateLimitedRequestCount
        );
    }
}
