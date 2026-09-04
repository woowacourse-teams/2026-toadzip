package com.toadzip.backend.housing.exception;

public class AdminHousingComplexNotFoundException extends RuntimeException {

    public AdminHousingComplexNotFoundException() {
        super("단지를 찾을 수 없습니다.");
    }
}
