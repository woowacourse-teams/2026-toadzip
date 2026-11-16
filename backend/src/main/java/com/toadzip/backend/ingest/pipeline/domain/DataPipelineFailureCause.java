package com.toadzip.backend.ingest.pipeline.domain;

/** 실행이 실패로 끝난 원인. 관리자가 다시 실행하면 되는지 판단하는 데 쓴다. */
public enum DataPipelineFailureCause {
    /** 외부 기관 응답·연결 문제나 서버 중단으로 멈췄다. 잠시 뒤 다시 실행하면 이어서 받는다. */
    TRANSIENT,
    /** 일부 대상만 실패했다. 대상별 원인은 실패 목록에 있다. */
    PARTIAL,
    /** 서버 내부 오류다. 다시 실행해도 같을 수 있어 실행 ID로 서버 로그를 확인해야 한다. */
    INTERNAL
}
