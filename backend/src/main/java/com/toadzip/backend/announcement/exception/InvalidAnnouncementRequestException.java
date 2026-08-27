package com.toadzip.backend.announcement.exception;

public class InvalidAnnouncementRequestException extends RuntimeException {

    public InvalidAnnouncementRequestException() {
        super("요청 값을 확인해 주세요.");
    }
}
