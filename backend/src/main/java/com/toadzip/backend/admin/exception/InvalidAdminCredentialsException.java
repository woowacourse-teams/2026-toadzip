package com.toadzip.backend.admin.exception;

public class InvalidAdminCredentialsException extends RuntimeException {

    public InvalidAdminCredentialsException() {
        super("관리자 로그인 정보가 올바르지 않습니다.");
    }
}
