package com.toadzip.backend.announcement.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.toadzip.backend.announcement.exception.InvalidAnnouncementCursorException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AnnouncementCursorCodecTest {

    private final AnnouncementCursorCodec cursorCodec = new AnnouncementCursorCodec();

    @Test
    void 날짜와_ID를_버전이_포함된_URL_안전_커서로_인코딩한다() {
        String cursor = cursorCodec.encode(LocalDate.of(2026, 8, 1), 42L);

        assertEquals("djF8MjAyNi0wOC0wMXw0Mg", cursor);
    }

    @Test
    void 커서에서_날짜와_ID를_복원한다() {
        AnnouncementCursorCodec.AnnouncementCursor cursor = cursorCodec.decode(
                "djF8MjAyNi0wOC0wMXw0Mg"
        );

        assertEquals(new AnnouncementCursorCodec.AnnouncementCursor(LocalDate.of(2026, 8, 1), 42L), cursor);
    }

    @ParameterizedTest
    @MethodSource("invalidCursors")
    void 정해진_형식이_아닌_커서는_기능_예외로_거절한다(String invalidCursor) {
        assertThrows(InvalidAnnouncementCursorException.class, () -> cursorCodec.decode(invalidCursor));
    }

    @Test
    void 예외_메시지에_입력_커서를_노출하지_않는다() {
        String invalidCursor = "secret-invalid-cursor";

        InvalidAnnouncementCursorException exception = assertThrows(
                InvalidAnnouncementCursorException.class,
                () -> cursorCodec.decode(invalidCursor)
        );

        assertNotNull(exception.getMessage());
        assertFalse(exception.getMessage().contains(invalidCursor));
    }

    @ParameterizedTest
    @MethodSource("invalidEncodeArguments")
    void 커서로_표현할_수_없는_날짜나_ID는_거절한다(LocalDate postedDate, long id) {
        assertThrows(InvalidAnnouncementCursorException.class, () -> cursorCodec.encode(postedDate, id));
    }

    private static Stream<Arguments> invalidCursors() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of(" "),
                Arguments.of("%%%"),
                Arguments.of(encodePayload("v1|2026-08-01")),
                Arguments.of(encodePayload("v2|2026-08-01|42")),
                Arguments.of(encodePayload("v1|2026-02-30|42")),
                Arguments.of(encodePayload("v1|2026-08-01|0")),
                Arguments.of(encodePayload("v1|2026-08-01|-1")),
                Arguments.of(encodePayload("v1|2026-08-01|42|extra")),
                Arguments.of(encodePayload("v1|2026-08-01|042")),
                Arguments.of("djF8MjAyNi0wOC0wMXw0Mg==")
        );
    }

    private static Stream<Arguments> invalidEncodeArguments() {
        return Stream.of(
                Arguments.of(null, 42L),
                Arguments.of(LocalDate.of(2026, 8, 1), 0L),
                Arguments.of(LocalDate.of(2026, 8, 1), -1L),
                Arguments.of(LocalDate.of(10_000, 1, 1), 42L)
        );
    }

    private static String encodePayload(String payload) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
