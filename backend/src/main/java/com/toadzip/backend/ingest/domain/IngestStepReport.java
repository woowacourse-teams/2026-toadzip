package com.toadzip.backend.ingest.domain;

/**
 * 파이프라인 단계가 결과 보고서에서 읽는 실패·호출 한도 실패·경고 수다. 무엇을 실패로, 무엇을 경고로 볼지는
 * 보고서가 정한다. 보고서를 그대로 응답 JSON으로 저장하므로 get 접두사 없는 접근자로 두어 키를 늘리지 않는다.
 */
public interface IngestStepReport {

    int stepFailureCount();

    default int stepRateLimitedFailureCount() {
        return 0;
    }

    default int stepWarningCount() {
        return 0;
    }

    /**
     * 실패가 있고 성공한 대상이 하나도 없다. 기관 응답 형식이 바뀌는 경우처럼 다시 해도 같을 수 있는 실패라, 일부만 실패한 실행과
     * 구별해 자동 실행이 성공으로 세지 않게 한다. 대상 수를 세지 않는 보고서는 알 수 없으므로 false다.
     */
    default boolean stepFailedEntirely() {
        return false;
    }
}
