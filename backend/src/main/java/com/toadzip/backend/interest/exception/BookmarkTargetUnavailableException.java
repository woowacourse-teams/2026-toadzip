package com.toadzip.backend.interest.exception;

public class BookmarkTargetUnavailableException extends RuntimeException {
    public BookmarkTargetUnavailableException() {
        super("저장할 대상을 찾을 수 없습니다.");
    }
}
