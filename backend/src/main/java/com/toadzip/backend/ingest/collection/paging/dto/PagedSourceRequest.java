package com.toadzip.backend.ingest.collection.paging.dto;

import com.toadzip.backend.ingest.collection.history.dto.SourceCollectionRequest;

/** 페이지로 나눠 받는 원천 요청이다. 페이지 호출의 설명과 성공 시 해소할 실패 기록의 범위를 요청이 정한다. */
public interface PagedSourceRequest extends SourceCollectionRequest {

    int pageSize();

    int maxPages();

    String pageDescription(int page);

    /** 실패한 페이지를 알 수 없는 실패를 기록할 때 쓰는 요청 설명이다. */
    default String failureDescription() {
        return pageDescription(1);
    }

    /** 수집이 끝까지 성공하면 이 접두사로 시작하는 실패 기록을 해소한다. */
    String resolvedFailurePrefix();
}
