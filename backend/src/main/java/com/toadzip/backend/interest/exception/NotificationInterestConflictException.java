package com.toadzip.backend.interest.exception;

public class NotificationInterestConflictException extends RuntimeException {

    public NotificationInterestConflictException() {
        super("동일한 이벤트 식별자를 다른 알림 요청에 사용할 수 없습니다.");
    }
}
