package com.toadzip.backend.user.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class UserTest {

    @Test
    void 로그인_식별정보와_생성일시로_유저를_생성한다() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 19, 12, 0);
        Method createMethod = assertDoesNotThrow(
                () -> User.class.getDeclaredMethod("create", String.class, LocalDateTime.class)
        );

        User user = assertDoesNotThrow(() -> (User) createMethod.invoke(null, "login-id", createdAt));

        assertEquals("login-id", user.getLoginIdentifier());
        assertEquals(createdAt, user.getCreatedAt());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void 로그인_식별정보는_비어_있을_수_없다(String loginIdentifier) {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 19, 12, 0);

        assertThrows(IllegalArgumentException.class, () -> User.create(loginIdentifier, createdAt));
    }

    @Test
    void 생성일시는_필수다() {
        assertThrows(IllegalArgumentException.class, () -> User.create("login-id", null));
    }
}
