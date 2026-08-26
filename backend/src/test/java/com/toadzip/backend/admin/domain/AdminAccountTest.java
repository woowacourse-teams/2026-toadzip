package com.toadzip.backend.admin.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AdminAccountTest {

    @Test
    void 관리자는_활성_ADMIN_권한으로_생성한다() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 26, 10, 0);

        AdminAccount adminAccount = AdminAccount.create("admin", "password-hash", createdAt);

        assertEquals(AdminRole.ADMIN, adminAccount.getRole());
        assertEquals(createdAt, adminAccount.getCreatedAt());
    }

    @Test
    void 빈_로그인_식별자로_생성할_수_없다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AdminAccount.create(" ", "password-hash", LocalDateTime.of(2026, 8, 26, 10, 0))
        );
    }
}
