package com.toadzip.backend.ingest.dto;

public record ExternalApiCollectionReport(String operation, int storedRowCount, int failedRequestCount) {

    public ExternalApiCollectionReport {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("수집 작업명은 필수입니다.");
        }
        if (storedRowCount < 0 || failedRequestCount < 0) {
            throw new IllegalArgumentException("수집 결과 개수는 음수일 수 없습니다.");
        }
    }

    public static ExternalApiCollectionReport empty(String operation) {
        return new ExternalApiCollectionReport(operation, 0, 0);
    }

    public ExternalApiCollectionReport plus(ExternalApiCollectionReport other) {
        if (!operation.equals(other.operation)) {
            throw new IllegalArgumentException("서로 다른 수집 작업은 합칠 수 없습니다.");
        }
        return new ExternalApiCollectionReport(
                operation,
                storedRowCount + other.storedRowCount,
                failedRequestCount + other.failedRequestCount
        );
    }
}
