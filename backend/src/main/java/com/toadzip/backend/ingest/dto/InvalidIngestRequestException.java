package com.toadzip.backend.ingest.dto;

public class InvalidIngestRequestException extends RuntimeException {

    public InvalidIngestRequestException(String message) {
        super(message);
    }
}
