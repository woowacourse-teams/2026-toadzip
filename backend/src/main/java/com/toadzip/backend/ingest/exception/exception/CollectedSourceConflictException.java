package com.toadzip.backend.ingest.exception.exception;

/**
 * 수집한 응답이 이미 저장된 원천과 충돌해 그 요청의 저장을 보류한다. 코드 오류가 아니라 응답 데이터의 문제이므로 페이지 수집은
 * 이 실패를 요청 실패로 기록하고 다른 요청을 계속한다.
 */
public class CollectedSourceConflictException extends RuntimeException {

    public CollectedSourceConflictException(String message) {
        super(message);
    }
}
