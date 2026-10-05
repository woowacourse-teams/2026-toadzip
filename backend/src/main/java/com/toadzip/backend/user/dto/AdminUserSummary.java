package com.toadzip.backend.user.dto;

import java.time.LocalDateTime;

public record AdminUserSummary(long id, String email, AdminUserProvider provider, LocalDateTime createdAt) {
}
