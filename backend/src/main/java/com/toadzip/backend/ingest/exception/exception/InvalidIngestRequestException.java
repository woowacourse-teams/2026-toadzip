package com.toadzip.backend.ingest.exception.exception;

public class InvalidIngestRequestException extends RuntimeException {

    public InvalidIngestRequestException(String message) {
        super(message);
    }
}
