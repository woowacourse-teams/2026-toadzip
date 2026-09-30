package com.toadzip.backend.ingest.exception.exception;

public class LhAnnouncementUnavailableException extends RuntimeException {

    private final boolean rateLimited;

    public LhAnnouncementUnavailableException(String message) {
        this(message, false);
    }

    public LhAnnouncementUnavailableException(String message, boolean rateLimited) {
        super(message);
        this.rateLimited = rateLimited;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }
}
