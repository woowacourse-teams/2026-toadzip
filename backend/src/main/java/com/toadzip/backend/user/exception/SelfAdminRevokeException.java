package com.toadzip.backend.user.exception;

public class SelfAdminRevokeException extends RuntimeException {

    public SelfAdminRevokeException() {
        super("자기 자신의 관리자 권한은 뺄 수 없습니다.");
    }
}
