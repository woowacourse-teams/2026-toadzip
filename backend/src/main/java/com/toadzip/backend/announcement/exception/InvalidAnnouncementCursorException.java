package com.toadzip.backend.announcement.exception;

public class InvalidAnnouncementCursorException extends RuntimeException {

    public InvalidAnnouncementCursorException() {
        super("공고 커서가 올바르지 않습니다.");
    }
}
