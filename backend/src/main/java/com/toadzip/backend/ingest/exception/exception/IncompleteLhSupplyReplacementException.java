package com.toadzip.backend.ingest.exception.exception;

public class IncompleteLhSupplyReplacementException extends RuntimeException {

    public IncompleteLhSupplyReplacementException(long missingRowCount) {
        super("LH 공급 수집 결과에 기존 공급행 " + missingRowCount + "건이 누락되어 원천을 교체하지 않습니다.");
    }
}
