package com.toadzip.backend.ingest.collection.lh.service;

public class EmptyLhDetailReplacementException extends RuntimeException {

    public EmptyLhDetailReplacementException() {
        super("기존 LH 상세 원천을 빈 수집 결과로 교체할 수 없습니다.");
    }
}
