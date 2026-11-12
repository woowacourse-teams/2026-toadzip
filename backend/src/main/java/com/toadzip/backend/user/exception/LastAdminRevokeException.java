package com.toadzip.backend.user.exception;

public class LastAdminRevokeException extends RuntimeException {

    public LastAdminRevokeException() {
        super("관리자 권한이 있는 카카오·구글 계정이 하나는 남아 있어야 합니다.");
    }
}
