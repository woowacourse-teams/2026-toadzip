package com.toadzip.backend.ingest.pipeline.dto;

/**
 * 실행 실패.
 *
 * @param cause TRANSIENT(잠시 뒤 다시 실행), PARTIAL(일부 대상 실패, 실패 목록 확인), INTERNAL(서버 내부 오류).
 *              원인을 남기기 전에 실패한 실행이면 null이다.
 * @param detail 원인 요약. 외부 기관 오류면 비밀값을 가린 메시지, 내부 오류면 예외 종류다. 없으면 null이다.
 */
public record DataPipelineFailureResponse(
        String stepName,
        String message,
        Object serverResponse,
        String cause,
        String detail
) {
}
