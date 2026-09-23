package com.toadzip.backend.ingest.exception.exception;

public class DataPipelineExecutionNotFoundException extends RuntimeException {

    public DataPipelineExecutionNotFoundException(String message) {
        super(message);
    }
}
