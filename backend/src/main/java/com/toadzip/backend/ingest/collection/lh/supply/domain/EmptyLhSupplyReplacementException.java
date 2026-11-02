package com.toadzip.backend.ingest.collection.lh.supply.domain;

public class EmptyLhSupplyReplacementException extends RuntimeException {

    public EmptyLhSupplyReplacementException() {
        super("기존 LH 공급 원천을 빈 수집 결과로 교체할 수 없습니다.");
    }
}
