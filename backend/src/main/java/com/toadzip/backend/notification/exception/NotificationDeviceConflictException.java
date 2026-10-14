package com.toadzip.backend.notification.exception;

public class NotificationDeviceConflictException extends RuntimeException {
    public NotificationDeviceConflictException() {
        super("기기 또는 계정 상태가 변경되었습니다. 다시 확인해 주세요.");
    }
}
