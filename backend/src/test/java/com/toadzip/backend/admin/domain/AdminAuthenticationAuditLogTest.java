package com.toadzip.backend.admin.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AdminAuthenticationAuditLogTest {

    @Test
    void 인증_감사_기록을_생성한다() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 26, 10, 0);

        AdminAuthenticationAuditLog auditLog = AdminAuthenticationAuditLog.create(
                AdminAuthenticationAuditAction.LOGIN,
                AdminAuthenticationAuditResult.SUCCESS,
                "admin",
                "trace-id",
                occurredAt
        );

        assertEquals(AdminAuthenticationAuditAction.LOGIN, auditLog.getAction());
        assertEquals(AdminAuthenticationAuditResult.SUCCESS, auditLog.getResult());
        assertEquals("admin", auditLog.getLoginIdentifier());
        assertEquals("trace-id", auditLog.getRequestTraceId());
        assertEquals(occurredAt, auditLog.getOccurredAt());
    }
}
