package com.toadzip.backend.housing.exception;

public class InvalidComplexRequestException extends RuntimeException {

    public InvalidComplexRequestException() {
        super("단지 조회 요청값이 올바르지 않습니다.");
    }
}
