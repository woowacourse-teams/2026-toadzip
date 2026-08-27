package com.toadzip.backend.admin.service;

import com.toadzip.backend.admin.domain.AdminAuthenticationAuditAction;
import com.toadzip.backend.admin.domain.AdminAuthenticationAuditLog;
import com.toadzip.backend.admin.domain.AdminAuthenticationAuditResult;
import com.toadzip.backend.admin.repository.AdminAuthenticationAuditLogRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAuthenticationAuditService {

    private final AdminAuthenticationAuditLogRepository auditLogRepository;

    @Transactional
    public void recordLoginSuccess(String loginIdentifier, String requestTraceId) {
        record(
                AdminAuthenticationAuditAction.LOGIN,
                AdminAuthenticationAuditResult.SUCCESS,
                loginIdentifier,
                requestTraceId
        );
    }

    @Transactional
    public void recordLoginFailure(String loginIdentifier, String requestTraceId) {
        record(
                AdminAuthenticationAuditAction.LOGIN,
                AdminAuthenticationAuditResult.FAILURE,
                loginIdentifier,
                requestTraceId
        );
    }

    @Transactional
    public void recordLogout(String loginIdentifier, String requestTraceId) {
        record(
                AdminAuthenticationAuditAction.LOGOUT,
                AdminAuthenticationAuditResult.SUCCESS,
                loginIdentifier,
                requestTraceId
        );
    }

    private void record(
            AdminAuthenticationAuditAction action,
            AdminAuthenticationAuditResult result,
            String loginIdentifier,
            String requestTraceId
    ) {
        AdminAuthenticationAuditLog auditLog = AdminAuthenticationAuditLog.create(
                action,
                result,
                loginIdentifier,
                requestTraceId,
                LocalDateTime.now()
        );
        auditLogRepository.save(auditLog);
    }
}
