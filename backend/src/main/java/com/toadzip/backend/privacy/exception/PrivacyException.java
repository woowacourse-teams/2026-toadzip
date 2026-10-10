package com.toadzip.backend.privacy.exception;

public class PrivacyException extends RuntimeException {

    private final String code;

    public PrivacyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
