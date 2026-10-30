package com.toadzip.backend.announcement.exception;

public class InvalidAnnouncementPdfException extends RuntimeException {

    public InvalidAnnouncementPdfException(String message) {
        super(message);
    }

    public InvalidAnnouncementPdfException(String message, Throwable cause) {
        super(message, cause);
    }
}
