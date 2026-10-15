package com.toadzip.backend.notification.exception;

public class NotificationNotFoundException extends RuntimeException {
    public NotificationNotFoundException() {
        super("알림을 찾을 수 없습니다.");
    }
}
