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
}
