package com.toadzip.backend.announcement.exception;

public class AnnouncementImportDuplicatedException extends RuntimeException {

    public AnnouncementImportDuplicatedException() {
        super("이미 등록된 공고 가져오기 요청입니다.");
    }
}
