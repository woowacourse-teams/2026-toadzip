package com.toadzip.backend.ingest.service;

public class IngestAlreadyRunningException extends RuntimeException {

    public IngestAlreadyRunningException(String message) {
        super(message);
    }
}
