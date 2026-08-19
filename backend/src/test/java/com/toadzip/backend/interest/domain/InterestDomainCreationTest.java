package com.toadzip.backend.interest.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.notice.domain.Notice;
import com.toadzip.backend.user.domain.User;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class InterestDomainCreationTest {

    @Test
    void 매칭된_공고가_없어도_관심공고를_생성한다() {
        User user = createUser();
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 19, 12, 30);
        Method createMethod = findCreateMethod(
                FavoriteNotice.class,
                User.class,
                Notice.class,
                LocalDateTime.class
        );

        FavoriteNotice favoriteNotice = invoke(createMethod, user, null, createdAt);

        assertEquals(user, favoriteNotice.getUser());
        assertNull(favoriteNotice.getNotice());
        assertEquals(createdAt, favoriteNotice.getCreatedAt());
    }

    @Test
    void 관심단지를_생성한다() {
        User user = createUser();
        HousingComplex housingComplex = createHousingComplex();
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 19, 12, 30);
        Method createMethod = findCreateMethod(
                FavoriteHousingComplex.class,
                User.class,
                HousingComplex.class,
                LocalDateTime.class
        );

        FavoriteHousingComplex favoriteHousingComplex = invoke(
                createMethod,
                user,
                housingComplex,
                createdAt
        );

        assertEquals(user, favoriteHousingComplex.getUser());
        assertEquals(housingComplex, favoriteHousingComplex.getHousingComplex());
        assertEquals(createdAt, favoriteHousingComplex.getCreatedAt());
    }

    @Test
    void 관심지역을_생성한다() {
        User user = createUser();
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 19, 12, 30);
        Method createMethod = findCreateMethod(
                FavoriteRegion.class,
                User.class,
                String.class,
                String.class,
                String.class,
                String.class,
                LocalDateTime.class
        );

        FavoriteRegion favoriteRegion = invoke(
                createMethod,
                user,
                "11",
                "서울특별시",
                "11140",
                "중구",
                createdAt
        );

        assertEquals(user, favoriteRegion.getUser());
        assertEquals("11", favoriteRegion.getProvinceCode());
        assertEquals("서울특별시", favoriteRegion.getProvinceName());
        assertEquals("11140", favoriteRegion.getCityCountyDistrictCode());
        assertEquals("중구", favoriteRegion.getCityCountyDistrictName());
        assertEquals(createdAt, favoriteRegion.getCreatedAt());
    }

    private User createUser() {
        return User.create("login-id", LocalDateTime.of(2026, 8, 19, 12, 0));
    }

    private HousingComplex createHousingComplex() {
        return HousingComplex.create(
                "두꺼비 행복주택",
                "source-complex-id",
                "행복주택",
                Address.create(
                        "서울특별시 중구 세종대로 110",
                        "1114010100100010000",
                        "1114010100",
                        "11",
                        "서울특별시",
                        "11140",
                        "중구",
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780")
                ),
                500,
                "LH",
                LocalDate.of(2020, 6, 30),
                "개별난방",
                "아파트",
                "계단식",
                true,
                420,
                "https://example.com/complex.png",
                25
        );
    }

    private Method findCreateMethod(Class<?> type, Class<?>... parameterTypes) {
        return assertDoesNotThrow(() -> type.getDeclaredMethod("create", parameterTypes));
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(Method method, Object... arguments) {
        return assertDoesNotThrow(() -> (T) method.invoke(null, arguments));
    }
}
