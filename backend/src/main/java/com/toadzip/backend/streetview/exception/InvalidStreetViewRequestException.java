package com.toadzip.backend.streetview.exception;

public class InvalidStreetViewRequestException extends RuntimeException {
    private final String field;

    public InvalidStreetViewRequestException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
