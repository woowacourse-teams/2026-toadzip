package com.toadzip.backend.interest.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.notice.domain.Notice;
import com.toadzip.backend.notice.domain.ReceptionPlace;
import com.toadzip.backend.user.domain.User;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InterestValidationTest {

    @Test
    void 관심공고의_유저와_공고와_등록일시는_필수다() {
        User user = createUser();
        Notice notice = createNotice();
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 19, 12, 30);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> FavoriteNotice.create(null, notice, createdAt)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> FavoriteNotice.create(user, null, createdAt)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> FavoriteNotice.create(user, notice, null)
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
    @ValueSource(ints = {0, 1})
    void 관심지역의_지역_문자열은_비어_있을_수_없다(int blankFieldIndex) {
        String[] fields = {"11", "11140"};
        fields[blankFieldIndex] = " ";

        assertThrows(
                IllegalArgumentException.class,
                () -> FavoriteRegion.create(
                        createUser(),
                        fields[0],
                        fields[1],
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
                        () -> FavoriteRegion.create(null, "11", "11140", createdAt)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> FavoriteRegion.create(user, "11", "11140", null)
                )
        );
    }

    private User createUser() {
        return User.create("login-id", LocalDateTime.of(2026, 8, 19, 12, 0));
    }

    private Notice createNotice() {
        return Notice.create(
                "source-notice-id",
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
                "https://example.com/notices/1",
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
}
