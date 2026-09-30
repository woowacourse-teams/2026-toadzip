package com.toadzip.backend.announcement.exception;

public class InvalidAnnouncementImportException extends RuntimeException {

    public InvalidAnnouncementImportException() {
        super("검토가 완료되지 않은 공고 가져오기 요청입니다.");
    }
}
