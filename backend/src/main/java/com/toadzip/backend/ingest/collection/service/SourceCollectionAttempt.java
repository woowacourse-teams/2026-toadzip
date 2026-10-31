package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.history.dto.SourceCollectionRequest;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 원천 수집 한 번의 이력 생명주기다. 이력을 열고 원천을 모은 뒤 저장하고, 실패하면 이력을 실패로 닫고 예외를 그대로 던진다.
 * 실행 소유권과 중지 요청을 확인한 뒤에만 요청을 만들어 이력의 시작 시각이 확인 대기를 포함하지 않게 하고,
 * 저장하기 직전에 실행 소유권을 다시 확인한다.
 */
public final class SourceCollectionAttempt<A extends SourceCollectionRequest> {

    private final A request;

    private SourceCollectionAttempt(A request) {
        this.request = request;
    }

    /** 실행 소유권과 중지 요청을 확인한 뒤 요청을 만든다. 확인에 걸리면 요청을 만들지 않는다. */
    public static <A extends SourceCollectionRequest> SourceCollectionAttempt<A> prepare(Supplier<A> newRequest) {
        verifyCanContinue();
        return new SourceCollectionAttempt<>(newRequest.get());
    }

    public A request() {
        return request;
    }

    /** 저장까지 마친 수집 이력의 ID를 돌려준다. */
    public <T> UUID run(SourceCollectionRecordService records, Function<A, T> collect, Store<A, T> store) {
        UUID recordId = records.start(request);
        try {
            T collected = collect.apply(request);
            IngestExecutionScope.verifyHeld();
            store.store(recordId, request, collected);
            return recordId;
        } catch (RuntimeException failure) {
            records.fail(recordId, request, failure);
            throw failure;
        }
    }

    /** 다음 외부 요청이나 페이지 처리 전에 실행 소유권과 중지 요청을 확인한다. */
    public static void verifyCanContinue() {
        IngestExecutionScope.verifyHeld();
        IngestExecutionScope.checkStopRequested();
    }

    @FunctionalInterface
    public interface Store<A, T> {

        void store(UUID recordId, A request, T collected);
    }
}
