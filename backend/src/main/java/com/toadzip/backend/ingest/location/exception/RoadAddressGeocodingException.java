package com.toadzip.backend.ingest.location.exception;

import com.toadzip.backend.ingest.location.domain.RoadAddressGeocodingFailureReason;

public class RoadAddressGeocodingException extends RuntimeException {

    private final RoadAddressGeocodingFailureReason reason;

    public RoadAddressGeocodingException(
            RoadAddressGeocodingFailureReason reason,
            String message
    ) {
        super(message);
        this.reason = reason;
    }

    public RoadAddressGeocodingException(
            RoadAddressGeocodingFailureReason reason,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.reason = reason;
    }

    public RoadAddressGeocodingFailureReason getReason() {
        return reason;
    }
}
