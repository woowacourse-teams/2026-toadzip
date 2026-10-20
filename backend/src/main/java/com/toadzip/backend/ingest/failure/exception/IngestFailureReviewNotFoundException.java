package com.toadzip.backend.ingest.failure.exception;

public class IngestFailureReviewNotFoundException extends RuntimeException {
    public IngestFailureReviewNotFoundException() {
        super("오류 기록을 찾을 수 없습니다. 목록을 새로 조회해 주세요.");
    }
}
