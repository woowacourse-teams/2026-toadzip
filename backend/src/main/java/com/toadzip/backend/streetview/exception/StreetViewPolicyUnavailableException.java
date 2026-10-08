package com.toadzip.backend.streetview.exception;

public class StreetViewPolicyUnavailableException extends RuntimeException {
    public StreetViewPolicyUnavailableException() {
        super("거리뷰 제공 정책을 확인할 수 없습니다.");
    }
}
