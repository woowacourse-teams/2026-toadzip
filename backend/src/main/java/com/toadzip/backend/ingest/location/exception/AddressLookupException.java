package com.toadzip.backend.ingest.location.exception;

/** 관리자 주소 검색 실패. unavailable이면 키·외부 API 문제, 아니면 검색어 문제다. */
public class AddressLookupException extends RuntimeException {

    private final boolean unavailable;

    public AddressLookupException(String message, boolean unavailable) {
        super(message);
        this.unavailable = unavailable;
    }

    public boolean isUnavailable() {
        return unavailable;
    }
}
