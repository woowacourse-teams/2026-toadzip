package com.toadzip.backend.ingest.collection.paging.service;

/** 함께 수집하는 단위들이 나누는 호출 제한 신호다. 한 단위가 호출 제한에 걸리면 다른 단위의 남은 외부 호출을 멈춘다. */
public interface RateLimitSignal {

    RateLimitSignal NONE = new RateLimitSignal() {
        @Override
        public void checkBeforeAttempt() {
        }

        @Override
        public void reached() {
        }
    };

    /** 외부 호출 시도와 재시도 대기 중 확인마다 부른다. 다른 단위가 호출 제한에 걸렸으면 예외로 수집을 멈춘다. */
    void checkBeforeAttempt();

    /** 이 단위가 호출 제한에 걸렸을 때 실패를 기록하기 전에 부른다. */
    void reached();
}
