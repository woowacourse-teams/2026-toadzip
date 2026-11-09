package com.toadzip.backend.ingest.exception.exception;

/** 서버에 공공데이터포털 수집 키가 설정되지 않아 외부 수집을 시작할 수 없다. */
public class IngestServiceKeyMissingException extends RuntimeException {

    public IngestServiceKeyMissingException() {
        super("서버 수집 키가 설정되지 않아 수집을 시작할 수 없습니다. "
                + "서버 환경변수 DATA_GO_KR_SERVICE_KEY를 설정한 뒤 다시 실행해 주세요.");
    }
}
