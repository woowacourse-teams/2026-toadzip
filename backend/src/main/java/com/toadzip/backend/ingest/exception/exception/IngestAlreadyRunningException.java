package com.toadzip.backend.ingest.exception.exception;

public class IngestAlreadyRunningException extends RuntimeException {

    public IngestAlreadyRunningException(String message) {
        super(message);
    }
}
