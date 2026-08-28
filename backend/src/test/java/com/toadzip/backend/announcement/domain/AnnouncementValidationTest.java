package com.toadzip.backend.announcement.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnnouncementValidationTest {

    @Test
    void 접수처명과_접수방식은_비어_있을_수_없다() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> ReceptionPlace.create(" ", "인터넷", null, "1600-1004", "https://apply.lh.or.kr")
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> ReceptionPlace.create("LH 청약센터", " ", null, "1600-1004",
                                "https://apply.lh.or.kr")
                )
        );
    }

    @Test
    void 접수처와_문의처는_비어_있을_수_있다() {
        assertAll(
                () -> assertDoesNotThrow(() -> ReceptionPlace.create(
                        "LH 청약센터",
                        "인터넷",
                        null,
                        null,
                        "https://apply.lh.or.kr"
                )),
                () -> assertDoesNotThrow(() -> createAnnouncement(
                        validStringFields(),
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 10),
                        LocalDate.of(2026, 8, 14),
                        LocalDate.of(2026, 9, 1),
                        100L,
                        null
                ))
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void 공고의_필수_문자열은_비어_있을_수_없다(int blankFieldIndex) {
        String[] fields = validStringFields();
        fields[blankFieldIndex] = " ";

        assertThrows(
                IllegalArgumentException.class,
                () -> createAnnouncement(
                        fields,
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 10),
                        LocalDate.of(2026, 8, 14),
                        LocalDate.of(2026, 9, 1),
                        100L,
                        createReceptionPlace()
                )
        );
    }

    @Test
    void 게시일과_접수일과_발표일은_필수다() {
        String[] fields = validStringFields();
        LocalDate postedDate = LocalDate.of(2026, 8, 1);
        LocalDate applicationStartDate = LocalDate.of(2026, 8, 10);
        LocalDate applicationEndDate = LocalDate.of(2026, 8, 14);
        LocalDate winnerAnnouncementDate = LocalDate.of(2026, 9, 1);
        ReceptionPlace receptionPlace = createReceptionPlace();

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createAnnouncement(fields, null, applicationStartDate, applicationEndDate,
                                winnerAnnouncementDate, 100L, receptionPlace)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createAnnouncement(fields, postedDate, null, applicationEndDate,
                                winnerAnnouncementDate, 100L, receptionPlace)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createAnnouncement(fields, postedDate, applicationStartDate, null,
                                winnerAnnouncementDate, 100L, receptionPlace)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createAnnouncement(fields, postedDate, applicationStartDate, applicationEndDate,
                                null, 100L, receptionPlace)
                )
        );
    }

    @Test
    void 접수_종료일은_접수_시작일보다_빠를_수_없다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> createAnnouncement(
                        validStringFields(),
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 14),
                        LocalDate.of(2026, 8, 10),
                        LocalDate.of(2026, 9, 1),
                        100L,
                        createReceptionPlace()
                )
        );
    }

    @Test
    void 조회수는_음수일_수_없다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> createAnnouncement(
                        validStringFields(),
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 10),
                        LocalDate.of(2026, 8, 14),
                        LocalDate.of(2026, 9, 1),
                        -1L,
                        createReceptionPlace()
                )
        );
    }

    private Announcement createAnnouncement(
            String[] fields,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            LocalDate winnerAnnouncementDate,
            long viewCount,
            ReceptionPlace receptionPlace
    ) {
        return Announcement.create(
                fields[0],
                null,
                null,
                fields[1],
                fields[2],
                fields[3],
                fields[4],
                fields[5],
                postedDate,
                applicationStartDate,
                applicationEndDate,
                winnerAnnouncementDate,
                fields[6],
                null,
                viewCount,
                receptionPlace
        );
    }

    private String[] validStringFields() {
        return new String[]{
                "source-announcement-id",
                "행복주택 모집공고",
                "원공고",
                "행복주택",
                "신규모집",
                "LH",
                "https://example.com/announcements/1"
        };
    }

    private ReceptionPlace createReceptionPlace() {
        return ReceptionPlace.create(
                "LH 청약센터",
                "인터넷",
                null,
                "1600-1004",
                "https://apply.lh.or.kr"
        );
    }
}
