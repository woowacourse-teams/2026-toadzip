package com.toadzip.backend.announcement.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;

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
                        SupplyCategory.NEW_SUPPLY
                )
        );
    }

    @Test
    void 공급행의_공고와_공급구분은_필수다() {
        Announcement announcement = createAnnouncement();
        String[] fields = validSupplyRowStringFields();
        YearMonth expectedMoveInMonth = YearMonth.of(2027, 3);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createSupplyRow(null, fields, 1, expectedMoveInMonth, SupplyCategory.NEW_SUPPLY)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createSupplyRow(announcement, fields, 1, expectedMoveInMonth, null)
                )
        );
    }

    @Test
    void 공급행의_표시순서는_음수일_수_없다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> createSupplyRow(createAnnouncement(), validSupplyRowStringFields(), -1, YearMonth.of(2027, 3),
                        SupplyCategory.NEW_SUPPLY)
        );
    }

    private SupplyRow createSupplyRow(
            Announcement announcement,
            String[] fields,
            int displayOrder,
            YearMonth expectedMoveInMonth,
            SupplyCategory supplyCategory
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
                "단지 식별자 불일치");
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
                List.of(ReceptionPlace.create("LH 청약센터", "인터넷", null, "1600-1004", "https://apply.lh.or.kr"))
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

}
