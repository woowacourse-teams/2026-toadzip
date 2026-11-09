package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;

class MyHomeComplexMappingRejectedException extends RuntimeException {

    private final MyHomeComplexMappingFailureReason reason;

    MyHomeComplexMappingRejectedException(MyHomeComplexMappingFailureReason reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    MyHomeComplexMappingFailureReason reason() {
        return reason;
    }
}
