package com.toadzip.backend.ingest.repository.external;

public class ExternalDataRequestException extends RuntimeException {

    private final boolean retryable;

    private final boolean rateLimited;

    public ExternalDataRequestException(String message) {
        this(message, null, false, false);
    }

    public ExternalDataRequestException(String message, Throwable cause) {
        this(message, cause, false, false);
    }

    private ExternalDataRequestException(
            String message,
            Throwable cause,
            boolean retryable,
            boolean rateLimited
    ) {
        super(message, cause);
        this.retryable = retryable;
        this.rateLimited = rateLimited;
    }

    public static ExternalDataRequestException retryable(String message, Throwable cause) {
        return new ExternalDataRequestException(message, cause, true, false);
    }

    public static ExternalDataRequestException retryable(String message) {
        return new ExternalDataRequestException(message, null, true, false);
    }

    public static ExternalDataRequestException rateLimited(
            String message,
            Throwable cause,
            boolean retryable
    ) {
        return new ExternalDataRequestException(message, cause, retryable, true);
    }

    public static ExternalDataRequestException rateLimited(String message) {
        return new ExternalDataRequestException(message, null, false, true);
    }

    public boolean isRetryable() {
        return retryable;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }
}
