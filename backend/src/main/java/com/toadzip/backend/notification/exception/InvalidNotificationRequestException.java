package com.toadzip.backend.notification.exception;

public class InvalidNotificationRequestException extends RuntimeException {
    public InvalidNotificationRequestException() {
        super("알림 요청을 확인해 주세요.");
    }
}
