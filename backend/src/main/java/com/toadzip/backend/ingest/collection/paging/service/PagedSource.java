package com.toadzip.backend.ingest.collection.paging.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.paging.domain.PageBuffer;
import com.toadzip.backend.ingest.collection.paging.dto.PagedSourceRequest;
import java.util.UUID;

/**
 * 페이지로 나눠 받는 원천 하나다. 원천은 페이지를 받고, 검증하며 모으고, 저장하는 방법만 정한다.
 * 재시도, 수집 이력, 실행 확인, 실패 기록은 {@link PagedSourceCollector}가 맡는다.
 */
public interface PagedSource<A extends PagedSourceRequest, P, C> {

    ExternalDataSource source();

    P fetch(A request, int page);

    PageBuffer<P, C> newBuffer(A request);

    void store(UUID recordId, A request, C collected);

    /** 외부 호출 실패를 기록할 때 함께 남기는 로그 문구다. */
    String failureLogMessage();

    /** 페이지마다 실행 진행을 알릴지 정한다. 여러 단위를 함께 모아 단위별로 진행을 알리는 원천은 끈다. */
    default boolean reportsPageProgress() {
        return true;
    }
}
