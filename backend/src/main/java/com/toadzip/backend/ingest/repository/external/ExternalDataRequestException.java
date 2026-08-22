package com.toadzip.backend.ingest.repository.external;

public class ExternalDataRequestException extends RuntimeException {

    public ExternalDataRequestException(String message) {
        super(message);
    }

    public ExternalDataRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
