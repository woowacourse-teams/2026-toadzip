package com.toadzip.backend.eligibility.exception;

public class EligibilityNotFoundException extends RuntimeException {
    public EligibilityNotFoundException() {
        super("요청한 자격 비교 데이터를 찾을 수 없습니다.");
    }
}
