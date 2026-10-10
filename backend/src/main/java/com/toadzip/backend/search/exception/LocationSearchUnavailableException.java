package com.toadzip.backend.search.exception;

public class LocationSearchUnavailableException extends RuntimeException {

    public LocationSearchUnavailableException() {
        super("위치 검색을 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }
}
