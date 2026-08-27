package com.toadzip.backend.housing.exception;

public class InvalidMapBoundsException extends RuntimeException {

    public InvalidMapBoundsException() {
        super("지도 범위 좌표가 올바르지 않습니다.");
    }
}
