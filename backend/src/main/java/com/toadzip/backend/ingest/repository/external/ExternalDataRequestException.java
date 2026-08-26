package com.toadzip.backend.ingest.repository.external;

public class ExternalDataRequestException extends RuntimeException {

    private final boolean retryable;

    public ExternalDataRequestException(String message) {
        this(message, null, false);
    }

    public ExternalDataRequestException(String message, Throwable cause) {
        this(message, cause, false);
    }

    private ExternalDataRequestException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public static ExternalDataRequestException retryable(String message, Throwable cause) {
        return new ExternalDataRequestException(message, cause, true);
    }

    public static ExternalDataRequestException retryable(String message) {
        return new ExternalDataRequestException(message, null, true);
    }

    public boolean isRetryable() {
        return retryable;
    }
}
