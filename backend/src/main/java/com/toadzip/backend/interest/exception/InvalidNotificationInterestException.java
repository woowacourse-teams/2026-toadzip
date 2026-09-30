package com.toadzip.backend.interest.exception;

public class InvalidNotificationInterestException extends RuntimeException {

    public InvalidNotificationInterestException() {
        super("알림 수요 수집 대상이 올바르지 않습니다.");
    }
}
