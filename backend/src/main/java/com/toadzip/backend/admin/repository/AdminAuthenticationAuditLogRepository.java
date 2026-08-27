package com.toadzip.backend.admin.repository;

import com.toadzip.backend.admin.domain.AdminAuthenticationAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAuthenticationAuditLogRepository extends JpaRepository<AdminAuthenticationAuditLog, Long> {
}
