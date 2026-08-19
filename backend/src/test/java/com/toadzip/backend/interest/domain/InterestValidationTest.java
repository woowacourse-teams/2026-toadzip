package com.toadzip.backend.interest.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.user.domain.User;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InterestValidationTest {

    @Test
    void 관심공고의_유저와_등록일시는_필수다() {
        User user = createUser();
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 19, 12, 30);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> FavoriteNotice.create(null, null, createdAt)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> FavoriteNotice.create(user, null, null)
                )
        );
    }

    @Test
    void 관심단지의_유저와_단지와_등록일시는_필수다() {
        User user = createUser();
        HousingComplex housingComplex = createHousingComplex();
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 19, 12, 30);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> FavoriteHousingComplex.create(null, housingComplex, createdAt)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> FavoriteHousingComplex.create(user, null, createdAt)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> FavoriteHousingComplex.create(user, housingComplex, null)
                )
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void 관심지역의_지역_문자열은_비어_있을_수_없다(int blankFieldIndex) {
        String[] fields = {"11", "서울특별시", "11140", "중구"};
        fields[blankFieldIndex] = " ";

        assertThrows(
                IllegalArgumentException.class,
                () -> FavoriteRegion.create(
                        createUser(),
                        fields[0],
                        fields[1],
                        fields[2],
                        fields[3],
                        LocalDateTime.of(2026, 8, 19, 12, 30)
                )
        );
    }

    @Test
    void 관심지역의_유저와_등록일시는_필수다() {
        User user = createUser();
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 19, 12, 30);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> FavoriteRegion.create(null, "11", "서울특별시", "11140", "중구", createdAt)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> FavoriteRegion.create(user, "11", "서울특별시", "11140", "중구", null)
                )
        );
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
}
