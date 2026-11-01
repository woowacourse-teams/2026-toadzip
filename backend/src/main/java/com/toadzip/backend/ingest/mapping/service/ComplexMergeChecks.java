package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.mapping.exception.ComplexMergeConflictException;

/** 단지 통합 요청을 거절할 때 쓰는 충돌 예외. 통합 미리보기·실행·복구가 같은 예외로 거절한다. */
final class ComplexMergeChecks {

    private ComplexMergeChecks() {
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw conflict(message);
        }
    }

    static ComplexMergeConflictException conflict(String message) {
        return new ComplexMergeConflictException(message);
    }
}
