package com.toadzip.backend.ingest.exception.exception;

public class EmptyLhSupplyReplacementException extends RuntimeException {

    public EmptyLhSupplyReplacementException() {
        super("기존 LH 공급 원천을 빈 수집 결과로 교체할 수 없습니다.");
    }
}
