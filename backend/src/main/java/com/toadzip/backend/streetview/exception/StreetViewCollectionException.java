package com.toadzip.backend.streetview.exception;

public class StreetViewCollectionException extends RuntimeException {
    public enum Reason { RATE_LIMIT, PAYLOAD_TOO_LARGE, CAPACITY, ATTEMPT_CONFLICT }
    private final Reason reason;

    public StreetViewCollectionException(Reason reason) {
        super("거리뷰 실행 결과를 접수할 수 없습니다.");
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
