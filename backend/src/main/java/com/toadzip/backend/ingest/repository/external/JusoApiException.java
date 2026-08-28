package com.toadzip.backend.ingest.repository.external;

import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason;

public class JusoApiException extends RuntimeException {

    private final RoadAddressGeocodingFailureReason reason;

    private final boolean retryable;

    JusoApiException(RoadAddressGeocodingFailureReason reason, String message) {
        this(reason, message, null, false);
    }

    JusoApiException(RoadAddressGeocodingFailureReason reason, String message, Throwable cause) {
        this(reason, message, cause, false);
    }

    JusoApiException(
            RoadAddressGeocodingFailureReason reason,
            String message,
            Throwable cause,
            boolean retryable
    ) {
        super(message, cause);
        this.reason = reason;
        this.retryable = retryable;
    }

    public RoadAddressGeocodingFailureReason getReason() {
        return reason;
    }

    boolean isRetryable() {
        return retryable;
    }
}
