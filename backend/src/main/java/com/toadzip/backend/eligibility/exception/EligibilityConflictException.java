package com.toadzip.backend.eligibility.exception;

public class EligibilityConflictException extends RuntimeException {
    public EligibilityConflictException() {
        super("데이터가 변경되었습니다. 최신 정보를 다시 조회해 주세요.");
    }
}
