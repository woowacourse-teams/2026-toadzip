package com.toadzip.backend.ingest.collection.service;

final class ExternalDataRetryInterruptedException extends RuntimeException {

    ExternalDataRetryInterruptedException(InterruptedException cause) {
        super("외부 API 재시도 대기가 중단되었습니다.", cause);
    }
}
