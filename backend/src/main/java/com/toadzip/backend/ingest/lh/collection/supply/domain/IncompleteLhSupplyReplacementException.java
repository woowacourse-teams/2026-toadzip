package com.toadzip.backend.ingest.lh.collection.supply.domain;

public class IncompleteLhSupplyReplacementException extends RuntimeException {

    public IncompleteLhSupplyReplacementException(String reason) {
        super(reason);
    }
}
