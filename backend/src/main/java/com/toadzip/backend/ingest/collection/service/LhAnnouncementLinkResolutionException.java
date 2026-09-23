package com.toadzip.backend.ingest.collection.service;

public class LhAnnouncementLinkResolutionException extends RuntimeException {

    private final Reason reason;

    public LhAnnouncementLinkResolutionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        REQUEST_UNSUPPORTED,
        LINK_NOT_FOUND,
        LINK_MISMATCH
    }
}
