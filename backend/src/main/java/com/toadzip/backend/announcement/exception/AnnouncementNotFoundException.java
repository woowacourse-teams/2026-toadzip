package com.toadzip.backend.announcement.exception;

public class AnnouncementNotFoundException extends RuntimeException {

    public AnnouncementNotFoundException() {
        super("모집 공고를 찾을 수 없습니다.");
    }
}
