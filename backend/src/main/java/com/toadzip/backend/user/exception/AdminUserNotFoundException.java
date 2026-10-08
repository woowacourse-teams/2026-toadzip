package com.toadzip.backend.user.exception;

public class AdminUserNotFoundException extends RuntimeException {

    public AdminUserNotFoundException() {
        super("회원을 찾을 수 없습니다.");
    }
}
