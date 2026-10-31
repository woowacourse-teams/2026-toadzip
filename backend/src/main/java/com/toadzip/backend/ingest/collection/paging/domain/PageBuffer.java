package com.toadzip.backend.ingest.collection.paging.domain;

import java.time.Instant;

/** 원천의 페이지를 검증하며 모으고, 다 모으면 저장할 원천 응답을 만든다. */
public interface PageBuffer<P, C> {

    void add(P page);

    /** 지금까지 받은 페이지가 알린 전체 건수다. 아직 받은 페이지가 없으면 음수다. */
    int totalCount();

    boolean isComplete();

    C finish(Instant collectedAt);
}
