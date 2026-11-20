package com.toadzip.backend.user.exception;

import org.springframework.security.access.AccessDeniedException;

public class AdminRoleRevokedException extends AccessDeniedException {

    public AdminRoleRevokedException() {
        super("관리자 권한이 필요합니다.");
    }
}
