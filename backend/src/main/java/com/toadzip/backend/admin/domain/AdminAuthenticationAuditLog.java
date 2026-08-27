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
@Table(name = "admin_authentication_audit_logs")
@NoArgsConstructor(access = PROTECTED)
public class AdminAuthenticationAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(STRING)
    @Column(nullable = false)
    private AdminAuthenticationAuditAction action;

    @Enumerated(STRING)
    @Column(nullable = false)
    private AdminAuthenticationAuditResult result;

    @Column(nullable = false)
    private String loginIdentifier;

    @Column(nullable = false)
    private String requestTraceId;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    private AdminAuthenticationAuditLog(
            AdminAuthenticationAuditAction action,
            AdminAuthenticationAuditResult result,
            String loginIdentifier,
            String requestTraceId,
            LocalDateTime occurredAt
    ) {
        validateRequired(action, "감사 행동");
        validateRequired(result, "감사 결과");
        validateNotBlank(loginIdentifier, "관리자 로그인 식별자");
        validateNotBlank(requestTraceId, "요청 식별자");
        validateRequired(occurredAt, "발생 시각");
        this.action = action;
        this.result = result;
        this.loginIdentifier = loginIdentifier;
        this.requestTraceId = requestTraceId;
        this.occurredAt = occurredAt;
    }

    public static AdminAuthenticationAuditLog create(
            AdminAuthenticationAuditAction action,
            AdminAuthenticationAuditResult result,
            String loginIdentifier,
            String requestTraceId,
            LocalDateTime occurredAt
    ) {
        return new AdminAuthenticationAuditLog(action, result, loginIdentifier, requestTraceId, occurredAt);
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
