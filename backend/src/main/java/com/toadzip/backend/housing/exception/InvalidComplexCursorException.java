package com.toadzip.backend.housing.exception;

public class InvalidComplexCursorException extends RuntimeException {

    public InvalidComplexCursorException() {
        super("단지 조회 커서가 올바르지 않습니다.");
    }
}
