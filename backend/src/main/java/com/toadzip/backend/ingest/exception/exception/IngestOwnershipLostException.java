package com.toadzip.backend.ingest.exception.exception;

public class IngestOwnershipLostException extends RuntimeException {

    public IngestOwnershipLostException() {
        super("데이터 수집·정제 실행의 소유권을 잃어 작업을 중단했습니다.");
    }

    public IngestOwnershipLostException(Throwable cause) {
        super("데이터 수집·정제 실행의 소유권을 확인할 수 없어 작업을 중단했습니다.", cause);
    }
}
