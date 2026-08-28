package com.toadzip.backend.ingest.dto;

import com.toadzip.backend.ingest.domain.MyHomeComplexMappingFailure;
import com.toadzip.backend.ingest.domain.MyHomeComplexMappingFailureReason;
import java.time.Instant;

public record MyHomeComplexMappingFailureResponse(
        String sourceKey,
        String sourceComplexIdentifier,
        MyHomeComplexMappingFailureReason reason,
        String detail,
        Instant occurredAt
) {

    public static MyHomeComplexMappingFailureResponse from(MyHomeComplexMappingFailure failure) {
        return new MyHomeComplexMappingFailureResponse(
                failure.getSourceKey(),
                failure.getSourceComplexIdentifier(),
                failure.getReason(),
                failure.getDetail(),
                failure.getOccurredAt()
        );
    }
}
