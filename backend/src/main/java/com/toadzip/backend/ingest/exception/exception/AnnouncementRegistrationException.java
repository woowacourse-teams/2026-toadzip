package com.toadzip.backend.ingest.exception.exception;

/** 비동기 단건 등록의 실패 사유로 관리자에게 전달할 수 있는 메시지이다. */
public class AnnouncementRegistrationException extends RuntimeException {

    public AnnouncementRegistrationException(String message) {
        super(message);
    }

    public AnnouncementRegistrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
