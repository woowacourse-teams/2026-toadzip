package com.toadzip.backend.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminLoginRequest(
        @NotBlank(message = "로그인 식별자는 필수입니다.") String loginIdentifier,
        @NotBlank(message = "비밀번호는 필수입니다.") String password
) {
}
