package com.toadzip.backend.housing.exception;

public class HousingComplexNotFoundException extends RuntimeException {

    public HousingComplexNotFoundException() {
        super("단지를 찾을 수 없습니다.");
    }
}
