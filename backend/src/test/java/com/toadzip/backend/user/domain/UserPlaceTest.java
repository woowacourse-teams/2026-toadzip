package com.toadzip.backend.user.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class UserPlaceTest {

    @Test
    void 유저가_저장한_장소를_생성한다() {
        User user = User.create("login-id", LocalDateTime.of(2026, 8, 19, 12, 0));
        BigDecimal latitude = new BigDecimal("37.5665");
        BigDecimal longitude = new BigDecimal("126.9780");
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 19, 12, 30);
        Method createMethod = assertDoesNotThrow(
                () -> UserPlace.class.getDeclaredMethod(
                        "create",
                        User.class,
                        String.class,
                        String.class,
                        BigDecimal.class,
                        BigDecimal.class,
                        LocalDateTime.class
                )
        );

        UserPlace place = assertDoesNotThrow(
                () -> (UserPlace) createMethod.invoke(
                        null,
                        user,
                        "회사",
                        "서울특별시 중구 세종대로 110",
                        latitude,
                        longitude,
                        createdAt
                )
        );

        assertEquals(user, place.getUser());
        assertEquals("회사", place.getName());
        assertEquals("서울특별시 중구 세종대로 110", place.getAddress());
        assertEquals(latitude, place.getLatitude());
        assertEquals(longitude, place.getLongitude());
        assertEquals(createdAt, place.getCreatedAt());
    }

    @Test
    void 장소의_소유_유저는_필수다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> UserPlace.create(
                        null,
                        "회사",
                        "서울특별시 중구 세종대로 110",
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780"),
                        LocalDateTime.of(2026, 8, 19, 12, 30)
                )
        );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void 장소명은_비어_있을_수_없다(String name) {
        User user = User.create("login-id", LocalDateTime.of(2026, 8, 19, 12, 0));

        assertThrows(
                IllegalArgumentException.class,
                () -> UserPlace.create(
                        user,
                        name,
                        "서울특별시 중구 세종대로 110",
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780"),
                        LocalDateTime.of(2026, 8, 19, 12, 30)
                )
        );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void 주소는_비어_있을_수_없다(String address) {
        User user = User.create("login-id", LocalDateTime.of(2026, 8, 19, 12, 0));

        assertThrows(
                IllegalArgumentException.class,
                () -> UserPlace.create(
                        user,
                        "회사",
                        address,
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780"),
                        LocalDateTime.of(2026, 8, 19, 12, 30)
                )
        );
    }

    @Test
    void 위도와_경도와_등록일시는_필수다() {
        User user = User.create("login-id", LocalDateTime.of(2026, 8, 19, 12, 0));

        assertThrows(
                IllegalArgumentException.class,
                () -> UserPlace.create(
                        user,
                        "회사",
                        "서울특별시 중구 세종대로 110",
                        null,
                        new BigDecimal("126.9780"),
                        LocalDateTime.of(2026, 8, 19, 12, 30)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> UserPlace.create(
                        user,
                        "회사",
                        "서울특별시 중구 세종대로 110",
                        new BigDecimal("37.5665"),
                        null,
                        LocalDateTime.of(2026, 8, 19, 12, 30)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> UserPlace.create(
                        user,
                        "회사",
                        "서울특별시 중구 세종대로 110",
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780"),
                        null
                )
        );
    }

    @Test
    void 위도는_영하_90도에서_90도_사이여야_한다() {
        assertAll(
                () -> assertDoesNotThrow(() -> createUserPlace(new BigDecimal("-90"), BigDecimal.ZERO)),
                () -> assertDoesNotThrow(() -> createUserPlace(new BigDecimal("90"), BigDecimal.ZERO)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserPlace(new BigDecimal("-90.0001"), BigDecimal.ZERO)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserPlace(new BigDecimal("90.0001"), BigDecimal.ZERO)
                )
        );
    }

    @Test
    void 경도는_영하_180도에서_180도_사이여야_한다() {
        assertAll(
                () -> assertDoesNotThrow(() -> createUserPlace(BigDecimal.ZERO, new BigDecimal("-180"))),
                () -> assertDoesNotThrow(() -> createUserPlace(BigDecimal.ZERO, new BigDecimal("180"))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserPlace(BigDecimal.ZERO, new BigDecimal("-180.0001"))
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createUserPlace(BigDecimal.ZERO, new BigDecimal("180.0001"))
                )
        );
    }

    private UserPlace createUserPlace(BigDecimal latitude, BigDecimal longitude) {
        return UserPlace.create(
                User.create("login-id", LocalDateTime.of(2026, 8, 19, 12, 0)),
                "회사",
                "서울특별시 중구 세종대로 110",
                latitude,
                longitude,
                LocalDateTime.of(2026, 8, 19, 12, 30)
        );
    }
}
