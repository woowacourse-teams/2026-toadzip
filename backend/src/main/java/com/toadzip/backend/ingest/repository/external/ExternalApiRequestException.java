package com.toadzip.backend.ingest.repository.external;

public class ExternalApiRequestException extends RuntimeException {

    public ExternalApiRequestException(String message) {
        super(message);
    }

    public ExternalApiRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
