package com.toadzip.backend.housing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.toadzip.backend.housing.domain.ComplexSort;
import com.toadzip.backend.housing.exception.InvalidComplexCursorException;
import com.toadzip.backend.housing.repository.ComplexSummaryCursor;
import com.toadzip.backend.housing.repository.ComplexSummaryCursor.DateValue;
import com.toadzip.backend.housing.repository.ComplexSummaryCursor.DecimalValue;

class HousingComplexCursorCodecTest {

    private final HousingComplexCursorCodec codec = new HousingComplexCursorCodec();

    @ParameterizedTest
    @MethodSource("typedCursors")
    void v2_커서는_정렬과_typed_value와_ID를_왕복한다(ComplexSummaryCursor expected) {
        String encoded = codec.encode(expected);

        assertEquals(expected, codec.decode(encoded, expected.sort()));
    }

    @Test
    void v2_커서는_정확한_URL_safe_unpadded_payload로_발급한다() {
        ComplexSummaryCursor cursor = new ComplexSummaryCursor(
                ComplexSort.DEPOSIT_ASC,
                new DecimalValue(new BigDecimal("50000000")),
                41L
        );

        assertEquals("djJ8REVQT1NJVF9BU0N8MHw1MDAwMDAwMHw0MQ", codec.encode(cursor));
    }

    @Test
    void 요청_sort와_커서_sort가_다르면_거부한다() {
        String cursor = encodedCursor("v2|DEPOSIT_ASC|0|50000000|41");

        assertThrows(
                InvalidComplexCursorException.class,
                () -> codec.decode(cursor, ComplexSort.AREA_DESC)
        );
    }

    @Test
    void v1은_최신공고_정렬에서만_해석한다() {
        String v1 = legacyCursor("2026-08-27", 41L);

        ComplexSummaryCursor decoded = codec.decode(v1, ComplexSort.LATEST_ANNOUNCEMENT);
        assertEquals(ComplexSort.LATEST_ANNOUNCEMENT, decoded.sort());
        assertEquals(new DateValue(LocalDate.of(2026, 8, 27)), decoded.primaryValue());
        assertEquals(41L, decoded.complexId());
        assertThrows(InvalidComplexCursorException.class, () -> codec.decode(v1, ComplexSort.DEPOSIT_ASC));
    }

    @Test
    void v1의_null_날짜는_null_primary_value로_해석한다() {
        ComplexSummaryCursor decoded = codec.decode(
                legacyCursor("~", 41L),
                ComplexSort.LATEST_ANNOUNCEMENT
        );

        assertEquals(new ComplexSummaryCursor(ComplexSort.LATEST_ANNOUNCEMENT, null, 41L), decoded);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "v2|LATEST_ANNOUNCEMENT|0|1.5|41",
            "v2|COMPLETION_DATE_DESC|0|1.5|41",
            "v2|DEPOSIT_ASC|0|2026-08-27|41",
            "v2|MONTHLY_RENT_ASC|0|2026-08-27|41",
            "v2|AREA_DESC|0|2026-08-27|41"
    })
    void sort와_다른_value_type의_v2_커서를_거부한다(String payload) {
        String cursor = encodedCursor(payload);
        ComplexSort requestedSort = ComplexSort.valueOf(payload.split("\\|")[1]);

        assertThrows(InvalidComplexCursorException.class, () -> codec.decode(cursor, requestedSort));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "v2|DEPOSIT_ASC|0|-1|41",
            "v2|MONTHLY_RENT_ASC|0|-0.01|41",
            "v2|AREA_DESC|0|-84.12|41"
    })
    void 음수_decimal_value의_v2_커서를_거부한다(String payload) {
        String cursor = encodedCursor(payload);
        ComplexSort requestedSort = ComplexSort.valueOf(payload.split("\\|")[1]);

        assertThrows(InvalidComplexCursorException.class, () -> codec.decode(cursor, requestedSort));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "v3|LATEST_ANNOUNCEMENT|0|2026-08-27|41",
            "v2|LATEST_ANNOUNCEMENT|0|2026-08-27",
            "v2|LATEST_ANNOUNCEMENT|0|2026-08-27|41|extra",
            "v2|UNKNOWN|0|2026-08-27|41",
            "v2|LATEST_ANNOUNCEMENT|2|2026-08-27|41",
            "v2|LATEST_ANNOUNCEMENT|1|2026-08-27|41",
            "v2|LATEST_ANNOUNCEMENT|0|~|41",
            "v2|LATEST_ANNOUNCEMENT|0||41",
            "v2|LATEST_ANNOUNCEMENT|0|2026-08-27|0",
            "v2|LATEST_ANNOUNCEMENT|0|2026-08-27|-1",
            "v2|LATEST_ANNOUNCEMENT|0|2026-08-27|not-id",
            "v1|2026-08-27|0",
            "v1|2026-08-27|-1"
    })
    void 구조가_잘못된_커서를_거부한다(String payload) {
        assertThrows(
                InvalidComplexCursorException.class,
                () -> codec.decode(encodedCursor(payload), ComplexSort.LATEST_ANNOUNCEMENT)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "bad", "djJ8TEFURVNUX0FOTk9VTkNFTUVOVHwwfjQx==", "ab+c"})
    void Base64가_잘못된_커서를_거부한다(String cursor) {
        assertThrows(
                InvalidComplexCursorException.class,
                () -> codec.decode(cursor, ComplexSort.LATEST_ANNOUNCEMENT)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidCursorsForEncoding")
    void 발급할_수_없는_typed_커서를_거부한다(ComplexSummaryCursor cursor) {
        assertThrows(InvalidComplexCursorException.class, () -> codec.encode(cursor));
    }

    private static Stream<ComplexSummaryCursor> typedCursors() {
        return Stream.of(
                new ComplexSummaryCursor(
                        ComplexSort.LATEST_ANNOUNCEMENT,
                        new DateValue(LocalDate.of(2026, 8, 27)),
                        41L
                ),
                new ComplexSummaryCursor(
                        ComplexSort.COMPLETION_DATE_DESC,
                        new DateValue(LocalDate.of(2030, 5, 1)),
                        42L
                ),
                new ComplexSummaryCursor(
                        ComplexSort.DEPOSIT_ASC,
                        new DecimalValue(new BigDecimal("50000000")),
                        43L
                ),
                new ComplexSummaryCursor(
                        ComplexSort.MONTHLY_RENT_ASC,
                        new DecimalValue(new BigDecimal("350000.50")),
                        44L
                ),
                new ComplexSummaryCursor(
                        ComplexSort.AREA_DESC,
                        new DecimalValue(new BigDecimal("84.120")),
                        45L
                ),
                new ComplexSummaryCursor(ComplexSort.LATEST_ANNOUNCEMENT, null, 46L),
                new ComplexSummaryCursor(ComplexSort.DEPOSIT_ASC, null, 47L)
        );
    }

    private static Stream<ComplexSummaryCursor> invalidCursorsForEncoding() {
        return Stream.of(
                new ComplexSummaryCursor(ComplexSort.LATEST_ANNOUNCEMENT, null, 0L),
                new ComplexSummaryCursor(ComplexSort.LATEST_ANNOUNCEMENT, null, -1L),
                new ComplexSummaryCursor(
                        ComplexSort.LATEST_ANNOUNCEMENT,
                        new DecimalValue(BigDecimal.ONE),
                        41L
                ),
                new ComplexSummaryCursor(
                        ComplexSort.DEPOSIT_ASC,
                        new DateValue(LocalDate.of(2026, 8, 27)),
                        41L
                ),
                new ComplexSummaryCursor(
                        ComplexSort.DEPOSIT_ASC,
                        new DecimalValue(new BigDecimal("-1")),
                        41L
                )
        );
    }

    private String legacyCursor(String rawPostedDate, long complexId) {
        return encodedCursor("v1|" + rawPostedDate + "|" + complexId);
    }

    private String encodedCursor(String payload) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
