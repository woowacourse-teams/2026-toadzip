package com.toadzip.backend.geocoding.repository.external;

import com.toadzip.backend.geocoding.exception.RoadAddressGeocodingFailureReason;

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
