package com.toadzip.backend.interest.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.user.domain.User;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class InterestDomainCreationTest {

    @Test
    void 관심공고를_생성한다() {
        User user = createUser();
        Announcement announcement = createAnnouncement();
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 19, 12, 30);
        Method createMethod = findCreateMethod(
                FavoriteAnnouncement.class,
                User.class,
                Announcement.class,
                LocalDateTime.class
        );

        FavoriteAnnouncement favoriteAnnouncement = invoke(createMethod, user, announcement, createdAt);

        assertEquals(user, favoriteAnnouncement.getUser());
        assertEquals(announcement, favoriteAnnouncement.getAnnouncement());
        assertEquals(createdAt, favoriteAnnouncement.getCreatedAt());
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
                LocalDateTime.class
        );

        FavoriteRegion favoriteRegion = invoke(
                createMethod,
                user,
                "11",
                "11140",
                createdAt
        );

        assertEquals(user, favoriteRegion.getUser());
        assertEquals("11", favoriteRegion.getProvinceCode());
        assertEquals("11140", favoriteRegion.getCityCountyDistrictCode());
        assertEquals(createdAt, favoriteRegion.getCreatedAt());
    }

    private User createUser() {
        return User.create("login-id", LocalDateTime.of(2026, 8, 19, 12, 0));
    }

    private Announcement createAnnouncement() {
        return Announcement.create(
                "source-announcement-id",
                null,
                null,
                "행복주택 모집공고",
                "원공고",
                "행복주택",
                "신규모집",
                "LH",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 14),
                LocalDate.of(2026, 9, 1),
                "https://example.com/announcements/1",
                null,
                0L,
                ReceptionPlace.create("LH 청약센터", "인터넷", null, "1600-1004", null)
        );
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
                        "11140",
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
