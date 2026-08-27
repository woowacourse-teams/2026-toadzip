package com.toadzip.backend.ingest.repository.external;

import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason;

public class JusoApiException extends RuntimeException {

    private final RoadAddressGeocodingFailureReason reason;

    JusoApiException(RoadAddressGeocodingFailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    JusoApiException(RoadAddressGeocodingFailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public RoadAddressGeocodingFailureReason getReason() {
        return reason;
    }
}
