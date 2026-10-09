package com.toadzip.backend.interest.exception;

public class NotificationMemberMissingException extends RuntimeException {

    public NotificationMemberMissingException() {
        super("로그인이 필요합니다.");
    }
}
