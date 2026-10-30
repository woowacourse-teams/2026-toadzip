package com.toadzip.backend.interest.exception;

public class BookmarkUserMissingException extends RuntimeException {
    public BookmarkUserMissingException() {
        super("로그인이 필요합니다.");
    }
}
