package com.toadzip.backend.ingest.dto;

public record MyHomeComplexCollectionReport(
        String operation,
        int storedApiDataCount,
        int failedRequestCount,
        int externalApiCallCount
) {

    public MyHomeComplexCollectionReport {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("수집 작업명은 필수입니다.");
        }
        if (storedApiDataCount < 0 || failedRequestCount < 0 || externalApiCallCount < 0) {
            throw new IllegalArgumentException("수집 결과 개수는 음수일 수 없습니다.");
        }
    }

    public static MyHomeComplexCollectionReport empty() {
        return new MyHomeComplexCollectionReport("myhome-complex", 0, 0, 0);
    }

    public MyHomeComplexCollectionReport plus(MyHomeComplexCollectionReport other) {
        return new MyHomeComplexCollectionReport(
                operation,
                storedApiDataCount + other.storedApiDataCount,
                failedRequestCount + other.failedRequestCount,
                externalApiCallCount + other.externalApiCallCount
        );
    }
}
