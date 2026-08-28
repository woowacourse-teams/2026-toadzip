package com.toadzip.backend.housing.exception;

public class InvalidRegionCodeException extends RuntimeException {

    public InvalidRegionCodeException() {
        super("지역 코드를 확인해 주세요.");
    }
}
