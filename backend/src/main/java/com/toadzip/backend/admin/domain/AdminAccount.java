package com.toadzip.backend.admin.domain;

import static jakarta.persistence.EnumType.STRING;
import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "admin_accounts")
@NoArgsConstructor(access = PROTECTED)
public class AdminAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String loginIdentifier;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(STRING)
    @Column(nullable = false)
    private AdminRole role;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private AdminAccount(String loginIdentifier, String passwordHash, LocalDateTime createdAt) {
        validateNotBlank(loginIdentifier, "관리자 로그인 식별자");
        validateNotBlank(passwordHash, "관리자 비밀번호 해시");
        validateRequired(createdAt, "생성일시");
        this.loginIdentifier = loginIdentifier;
        this.passwordHash = passwordHash;
        this.role = AdminRole.ADMIN;
        this.createdAt = createdAt;
    }

    public static AdminAccount create(String loginIdentifier, String passwordHash, LocalDateTime createdAt) {
        return new AdminAccount(loginIdentifier, passwordHash, createdAt);
    }

    private void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }

    private void validateRequired(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }
}
