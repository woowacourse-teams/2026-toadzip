package com.toadzip.backend.ingest.exception.exception;

public final class ExternalDataRetryInterruptedException extends RuntimeException {

    public ExternalDataRetryInterruptedException(InterruptedException cause) {
        super("외부 API 재시도 대기가 중단되었습니다.", cause);
    }
}
