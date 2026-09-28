package com.toadzip.backend.announcement.exception;

public class AttachmentUnavailableException extends RuntimeException {
    public enum Reason {
        NOT_FOUND, UNSUPPORTED_SOURCE, NOT_PDF, TOO_LARGE, UPSTREAM_FAILURE, BUSY
    }

    private final Reason reason;

    public AttachmentUnavailableException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
