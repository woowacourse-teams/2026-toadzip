package com.toadzip.backend.ingest.collection.repository.external;

/**
 * 원천 앞의 차단기가 실제 요청을 보내기 전에 거절했다. 외부 호출로 세지 않고 재시도하지 않는다.
 * 호출 한도 때문에 닫힌 차단기의 거절이면 호출 한도 실패로 센다.
 */
public abstract class ExternalDataRequestRejectedException extends RuntimeException {

    private final boolean rateLimited;

    protected ExternalDataRequestRejectedException(String message, boolean rateLimited) {
        super(message);
        this.rateLimited = rateLimited;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }
}
