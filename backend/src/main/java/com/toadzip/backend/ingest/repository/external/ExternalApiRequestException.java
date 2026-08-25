package com.toadzip.backend.ingest.repository.external;

public class ExternalApiRequestException extends RuntimeException {

    private final boolean retryable;

    public ExternalApiRequestException(String message) {
        this(message, null, false);
    }

    public ExternalApiRequestException(String message, Throwable cause) {
        this(message, cause, false);
    }

    private ExternalApiRequestException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public static ExternalApiRequestException retryable(String message, Throwable cause) {
        return new ExternalApiRequestException(message, cause, true);
    }

    public boolean isRetryable() {
        return retryable;
    }
}
