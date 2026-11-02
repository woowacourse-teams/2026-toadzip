package com.toadzip.backend.ingest.collection.paging.domain;

import java.time.Instant;

/**
 * 원천의 페이지를 검증하며 모으고, 다 모으면 저장할 원천 응답을 만든다. 응답 검증은 {@link #finish}까지 모두 끝내고 검증
 * 실패는 {@link IllegalArgumentException}으로 던진다. 이 예외는 그 요청의 호출 실패로 기록된다. 저장 단계에서 난 같은
 * 예외는 내부 오류로 그대로 던져지므로, 응답 검증을 저장 단계로 미루지 않는다.
 */
public interface PageBuffer<P, C> {

    void add(P page);

    /** 지금까지 받은 페이지가 알린 전체 건수다. 아직 받은 페이지가 없으면 음수다. */
    int totalCount();

    boolean isComplete();

    C finish(Instant collectedAt);
}
