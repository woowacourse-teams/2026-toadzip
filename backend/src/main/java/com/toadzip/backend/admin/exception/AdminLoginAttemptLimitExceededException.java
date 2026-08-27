package com.toadzip.backend.admin.exception;

public class AdminLoginAttemptLimitExceededException extends RuntimeException {

    public AdminLoginAttemptLimitExceededException() {
        super("로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
    }
}
