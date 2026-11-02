package com.toadzip.backend.ingest.collection.lh.supply.domain;

public class IncompleteLhSupplyReplacementException extends RuntimeException {

    public IncompleteLhSupplyReplacementException(String reason) {
        super(reason);
    }
}
