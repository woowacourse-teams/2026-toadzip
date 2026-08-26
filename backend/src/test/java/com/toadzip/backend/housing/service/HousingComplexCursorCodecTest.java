package com.toadzip.backend.housing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.toadzip.backend.housing.exception.InvalidComplexCursorException;

class HousingComplexCursorCodecTest {

    private final HousingComplexCursorCodec codec = new HousingComplexCursorCodec();

    @Test
    void 게시일과_단지_ID를_URL_safe_커서로_왕복한다() {
        String cursor = codec.encode(LocalDate.of(2026, 8, 26), 41L);

        assertEquals(
                new HousingComplexCursorCodec.HousingComplexCursor(LocalDate.of(2026, 8, 26), 41L),
                codec.decode(cursor)
        );
    }

    @Test
    void 대표_공고가_없는_단지_커서를_왕복한다() {
        String cursor = codec.encode(null, 41L);

        assertNull(codec.decode(cursor).postedDate());
        assertEquals(41L, codec.decode(cursor).complexId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "bad", "djJ8MjAyNi0wOC0yNnw0MQ=="})
    void 잘못된_커서를_거부한다(String cursor) {
        assertThrows(InvalidComplexCursorException.class, () -> codec.decode(cursor));
    }
}
