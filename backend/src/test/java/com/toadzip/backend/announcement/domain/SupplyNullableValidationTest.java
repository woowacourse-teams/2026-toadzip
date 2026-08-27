package com.toadzip.backend.announcement.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SupplyNullableValidationTest {

    @Test
    void 미확인_공급값은_null로_보존한다() {
        SupplyRow supplyRow = SupplyRow.create(
                createAnnouncement(),
                null,
                null,
                "row-1",
                1,
                "원문 단지",
                "36A",
                "1114010100100010000",
                null,
                SupplyCategory.NEW_SUPPLY,
                "미매칭",
                null
        );
        SupplyTarget supplyTarget = SupplyTarget.create(
                supplyRow,
                "청년",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1
        );

        assertAll(
                () -> assertNull(supplyRow.getExpectedMoveInMonth()),
                () -> assertNull(supplyRow.getTotalSupplyHouseholdCount()),
                () -> assertNull(supplyTarget.getSupplyRank()),
                () -> assertNull(supplyTarget.getSupplyHouseholdCount()),
                () -> assertNull(supplyTarget.getReserveCount()),
                () -> assertNull(supplyTarget.getRentalDeposit()),
                () -> assertNull(supplyTarget.getMonthlyRent()),
                () -> assertNull(supplyTarget.getConvertedDeposit()),
                () -> assertNull(supplyTarget.getApplicationCondition())
        );
    }

    @Test
    void 선택_수량은_음수일_수_없다() {
        SupplyRow supplyRow = createSupplyRow();

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> SupplyRow.create(
                                createAnnouncement(), null, null, "row-2", 1, "원문 단지", "36A",
                                "1114010100100010000", null, SupplyCategory.NEW_SUPPLY, null, -1
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> SupplyTarget.create(supplyRow, "청년", null, -1, null, null, null, null, null, 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> SupplyTarget.create(supplyRow, "청년", null, null, -1, null, null, null, null, 1)
                )
        );
    }

    @Test
    void 선택_금액은_Long_범위의_음이_아닌_정수여야_한다() {
        SupplyRow supplyRow = createSupplyRow();

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> SupplyTarget.create(
                                supplyRow, "청년", null, null, null, new BigDecimal("1.5"), null, null, null, 1
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> SupplyTarget.create(
                                supplyRow,
                                "청년",
                                null,
                                null,
                                null,
                                null,
                                new BigDecimal("9223372036854775808"),
                                null,
                                null,
                                1
                        )
                )
        );
    }

    @Test
    void 선택_문자열은_존재하면_비어_있을_수_없다() {
        SupplyRow supplyRow = createSupplyRow();

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> SupplyRow.create(
                                createAnnouncement(), null, null, "row-2", 1, "원문 단지", "36A",
                                "1114010100100010000", null, SupplyCategory.NEW_SUPPLY, " ", null
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> SupplyTarget.create(supplyRow, "청년", " ", null, null, null, null, null, null, 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> SupplyTarget.create(supplyRow, "청년", null, null, null, null, null, null, " ", 1)
                )
        );
    }

    @Test
    void 경쟁률은_null_또는_음이_아닌_값만_허용한다() {
        assertAll(
                () -> assertDoesNotThrow(() -> createAnnouncement(null, null)),
                () -> assertDoesNotThrow(() -> createAnnouncement(new BigDecimal("1.2500"), new BigDecimal("2.5000"))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createAnnouncement(new BigDecimal("-0.0001"), null)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createAnnouncement(null, new BigDecimal("-0.0001"))
                )
        );
    }

    private SupplyRow createSupplyRow() {
        return SupplyRow.create(
                createAnnouncement(),
                null,
                null,
                "row-1",
                1,
                "원문 단지",
                "36A",
                "1114010100100010000",
                null,
                SupplyCategory.NEW_SUPPLY,
                "미매칭",
                null
        );
    }

    private Announcement createAnnouncement() {
        return createAnnouncement(null, null);
    }

    private Announcement createAnnouncement(BigDecimal actualCompetitionRate, BigDecimal predictedCompetitionRate) {
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
                actualCompetitionRate,
                predictedCompetitionRate,
                ReceptionPlace.create("LH 청약센터", "인터넷", null, "1600-1004", "https://apply.lh.or.kr")
        );
    }
}
