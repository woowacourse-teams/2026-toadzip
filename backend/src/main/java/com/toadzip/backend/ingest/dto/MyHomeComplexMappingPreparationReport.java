package com.toadzip.backend.ingest.dto;

public record MyHomeComplexMappingPreparationReport(
        int stagedCandidateCount,
        int failedSourceRowCount
) {

    public MyHomeComplexMappingPreparationReport {
        if (stagedCandidateCount < 0 || failedSourceRowCount < 0) {
            throw new IllegalArgumentException("준비 결과 개수는 음수일 수 없습니다.");
        }
    }
}
