package com.toadzip.backend.admin.dto;

import com.toadzip.backend.admin.domain.AdminRole;

public record AdminSessionResponse(String loginIdentifier, AdminRole role) {

    public static AdminSessionResponse from(String loginIdentifier) {
        return new AdminSessionResponse(loginIdentifier, AdminRole.ADMIN);
    }
}
