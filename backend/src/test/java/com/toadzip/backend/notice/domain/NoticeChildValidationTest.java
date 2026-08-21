package com.toadzip.backend.notice.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NoticeChildValidationTest {

    @Test
    void 공고일정의_공고와_시작일시와_종료일시는_필수다() {
        Notice notice = createNotice();
        LocalDateTime startAt = LocalDateTime.of(2026, 8, 10, 10, 0);
        LocalDateTime endAt = LocalDateTime.of(2026, 8, 14, 17, 0);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> NoticeSchedule.create(null, "접수", "인터넷 접수", startAt, endAt, 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> NoticeSchedule.create(notice, "접수", "인터넷 접수", null, endAt, 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> NoticeSchedule.create(notice, "접수", "인터넷 접수", startAt, null, 1)
                )
        );
    }

    @Test
    void 일정유형과_일정명은_비어_있을_수_없다() {
        Notice notice = createNotice();
        LocalDateTime startAt = LocalDateTime.of(2026, 8, 10, 10, 0);
        LocalDateTime endAt = LocalDateTime.of(2026, 8, 14, 17, 0);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> NoticeSchedule.create(notice, " ", "인터넷 접수", startAt, endAt, 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> NoticeSchedule.create(notice, "접수", " ", startAt, endAt, 1)
                )
        );
    }

    @Test
    void 일정_종료일시는_시작일시보다_빠를_수_없다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NoticeSchedule.create(
                        createNotice(),
                        "접수",
                        "인터넷 접수",
                        LocalDateTime.of(2026, 8, 14, 17, 0),
                        LocalDateTime.of(2026, 8, 10, 10, 0),
                        1
                )
        );
    }

    @Test
    void 공고일정의_표시순서는_음수일_수_없다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NoticeSchedule.create(
                        createNotice(),
                        "접수",
                        "인터넷 접수",
                        LocalDateTime.of(2026, 8, 10, 10, 0),
                        LocalDateTime.of(2026, 8, 14, 17, 0),
                        -1
                )
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void 첨부파일의_문자열은_비어_있을_수_없다(int blankFieldIndex) {
        String[] fields = {"모집공고문.pdf", "공고문", "https://example.com/files/notice.pdf"};
        fields[blankFieldIndex] = " ";

        assertThrows(
                IllegalArgumentException.class,
                () -> NoticeAttachment.create(createNotice(), fields[0], fields[1], fields[2], 1)
        );
    }

    @Test
    void 첨부파일의_공고는_필수이고_표시순서는_음수일_수_없다() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> NoticeAttachment.create(
                                null,
                                "모집공고문.pdf",
                                "공고문",
                                "https://example.com/files/notice.pdf",
                                1
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> NoticeAttachment.create(
                                createNotice(),
                                "모집공고문.pdf",
                                "공고문",
                                "https://example.com/files/notice.pdf",
                                -1
                        )
                )
        );
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
                ReceptionPlace.create("LH 청약센터", "인터넷", null, "1600-1004", "https://apply.lh.or.kr")
        );
    }
}
