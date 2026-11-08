package com.toadzip.backend.ingest.location.dto;

/** 주소 검색 후보와 같은 곳에 이미 등록된 단지. 관리자가 같은 단지를 또 등록하지 않게 먼저 보인다. 임대유형을 모르면 비어 있다. */
public record RegisteredComplex(long housingComplexId, String name, String roadAddress, String rentalType) {
}
