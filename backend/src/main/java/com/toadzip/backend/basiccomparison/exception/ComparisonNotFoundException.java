package com.toadzip.backend.basiccomparison.exception;

public class ComparisonNotFoundException extends RuntimeException {
    public ComparisonNotFoundException() {
        super("요청한 기본 비교 데이터를 찾을 수 없습니다.");
    }
}
