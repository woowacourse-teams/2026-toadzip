package com.toadzip.backend.ingest.dto;

import com.toadzip.backend.ingest.domain.MyHomeAnnouncementMappingFailure;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementMappingFailureReason;
import java.time.Instant;

public record MyHomeAnnouncementMappingFailureResponse(
        String sourceKey,
        String sourceAnnouncementIdentifier,
        Integer sourceHouseSerialNumber,
        MyHomeAnnouncementMappingFailureReason reason,
        String detail,
        Instant occurredAt
) {

    public static MyHomeAnnouncementMappingFailureResponse from(MyHomeAnnouncementMappingFailure failure) {
        return new MyHomeAnnouncementMappingFailureResponse(
                failure.getSourceKey(),
                failure.getSourceAnnouncementIdentifier(),
                failure.getSourceHouseSerialNumber(),
                failure.getReason(),
                failure.getDetail(),
                failure.getOccurredAt()
        );
    }
}
