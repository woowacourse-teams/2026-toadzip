package com.toadzip.backend.interest.exception;

public class NotificationSettingsConflictException extends RuntimeException {

    public NotificationSettingsConflictException() {
        super("회원 또는 알림 설정이 변경되었습니다. 현재 설정을 확인해 주세요.");
    }
}
