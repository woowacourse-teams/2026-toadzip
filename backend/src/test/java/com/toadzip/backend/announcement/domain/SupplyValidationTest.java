package com.toadzip.backend.announcement.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SupplyValidationTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void 공급행의_원천_문자열은_비어_있을_수_없다(int blankFieldIndex) {
        String[] fields = validSupplyRowStringFields();
        fields[blankFieldIndex] = " ";

        assertThrows(
                IllegalArgumentException.class,
                () -> createSupplyRow(
                        createAnnouncement(),
                        fields,
                        1,
                        YearMonth.of(2027, 3),
                        SupplyCategory.NEW_SUPPLY,
                        20
                )
        );
    }

    @Test
    void 공급행의_공고와_입주예정연월과_공급구분은_필수다() {
        Announcement announcement = createAnnouncement();
        String[] fields = validSupplyRowStringFields();
        YearMonth expectedMoveInMonth = YearMonth.of(2027, 3);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createSupplyRow(null, fields, 1, expectedMoveInMonth, SupplyCategory.NEW_SUPPLY, 20)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createSupplyRow(announcement, fields, 1, null, SupplyCategory.NEW_SUPPLY, 20)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createSupplyRow(announcement, fields, 1, expectedMoveInMonth, null, 20)
                )
        );
    }

    @Test
    void 공급행의_표시순서와_전체_공급세대수는_음수일_수_없다() {
        Announcement announcement = createAnnouncement();
        String[] fields = validSupplyRowStringFields();
        YearMonth expectedMoveInMonth = YearMonth.of(2027, 3);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createSupplyRow(announcement, fields, -1, expectedMoveInMonth,
                                SupplyCategory.NEW_SUPPLY, 20)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createSupplyRow(announcement, fields, 1, expectedMoveInMonth,
                                SupplyCategory.NEW_SUPPLY, -1)
                )
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void 공급대상의_문자열은_비어_있을_수_없다(int blankFieldIndex) {
        String[] fields = validSupplyTargetStringFields();
        fields[blankFieldIndex] = " ";

        assertThrows(
                IllegalArgumentException.class,
                () -> createSupplyTarget(
                        createSupplyRow(),
                        fields,
                        10,
                        20,
                        new BigDecimal("50000000"),
                        new BigDecimal("250000"),
                        new BigDecimal("70000000"),
                        1
                )
        );
    }

    @Test
    void 공급대상의_공급행과_임대보증금과_월임대료는_필수다() {
        SupplyRow supplyRow = createSupplyRow();
        String[] fields = validSupplyTargetStringFields();
        BigDecimal amount = new BigDecimal("1000000");

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createSupplyTarget(null, fields, 10, 20, amount, amount, amount, 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createSupplyTarget(supplyRow, fields, 10, 20, null, amount, amount, 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createSupplyTarget(supplyRow, fields, 10, 20, amount, null, amount, 1)
                )
        );
    }

    @Test
    void 공급대상의_수량과_금액과_표시순서는_음수일_수_없다() {
        SupplyRow supplyRow = createSupplyRow();
        String[] fields = validSupplyTargetStringFields();
        BigDecimal amount = new BigDecimal("1000000");
        BigDecimal negative = BigDecimal.ONE.negate();

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createSupplyTarget(supplyRow, fields, -1, 20, amount, amount, amount, 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createSupplyTarget(supplyRow, fields, 10, -1, amount, amount, amount, 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createSupplyTarget(supplyRow, fields, 10, 20, negative, amount, amount, 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createSupplyTarget(supplyRow, fields, 10, 20, amount, negative, amount, 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createSupplyTarget(supplyRow, fields, 10, 20, amount, amount, negative, 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createSupplyTarget(supplyRow, fields, 10, 20, amount, amount, amount, -1)
                )
        );
    }

    private SupplyRow createSupplyRow() {
        return createSupplyRow(
                createAnnouncement(),
                validSupplyRowStringFields(),
                1,
                YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY,
                20
        );
    }

    private SupplyRow createSupplyRow(
            Announcement announcement,
            String[] fields,
            int displayOrder,
            YearMonth expectedMoveInMonth,
            SupplyCategory supplyCategory,
            int totalSupplyHouseholdCount
    ) {
        return SupplyRow.create(
                announcement,
                null,
                null,
                fields[0],
                displayOrder,
                fields[1],
                fields[2],
                fields[3],
                expectedMoveInMonth,
                supplyCategory,
                "단지 식별자 불일치",
                totalSupplyHouseholdCount
        );
    }

    private SupplyTarget createSupplyTarget(
            SupplyRow supplyRow,
            String[] fields,
            int supplyHouseholdCount,
            int reserveCount,
            BigDecimal rentalDeposit,
            BigDecimal monthlyRent,
            BigDecimal convertedDeposit,
            int displayOrder
    ) {
        return SupplyTarget.create(
                supplyRow,
                fields[0],
                fields[1],
                supplyHouseholdCount,
                reserveCount,
                rentalDeposit,
                monthlyRent,
                convertedDeposit,
                fields[2],
                displayOrder
        );
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
                ReceptionPlace.create("LH 청약센터", "인터넷", null, "1600-1004", "https://apply.lh.or.kr")
        );
    }

    private String[] validSupplyRowStringFields() {
        return new String[]{
                "source-supply-row-id",
                "원천 단지명",
                "36A",
                "1114010100100010000"
        };
    }

    private String[] validSupplyTargetStringFields() {
        return new String[]{"청년", "1순위", "소득 기준 충족"};
    }
}
