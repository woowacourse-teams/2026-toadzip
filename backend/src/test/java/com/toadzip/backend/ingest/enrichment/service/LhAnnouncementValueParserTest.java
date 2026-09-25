package com.toadzip.backend.ingest.enrichment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LhAnnouncementValueParserTest {

    private final LhAnnouncementValueParser parser = new LhAnnouncementValueParser();

    @Test
    void 날짜만_있는_기간의_뒤_연도를_앞_날짜의_시각으로_읽지_않는다() {
        assertThat(parser.dateTimes("2026.09.14 ~ 2026.09.15", "접수"))
                .containsExactly(LocalDateTime.of(2026, 9, 14, 0, 0), LocalDateTime.of(2026, 9, 15, 0, 0));
    }

    @Test
    void 시각_포함_및_혼합_기간을_각각_파싱한다() {
        assertThat(parser.dateTimes("2026.09.14 10:00 ~ 2026.09.15 16:00", "접수"))
                .containsExactly(LocalDateTime.of(2026, 9, 14, 10, 0), LocalDateTime.of(2026, 9, 15, 16, 0));
        assertThat(parser.dateTimes("2026.09.14 ~ 2026.09.15 16:00", "접수"))
                .containsExactly(LocalDateTime.of(2026, 9, 14, 0, 0), LocalDateTime.of(2026, 9, 15, 16, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026.02.30 ~ 2026.03.01",
            "접수 2026.09.14 ~ 2026.09.15",
            "2026.09.14 ~ 2026.09.15, 2026.09.16 ~ 2026.09.17",
            "2026.09.14 ~ 미정"
    })
    void 잘못된_기간을_부분_매칭해_성공시키지_않는다(String value) {
        assertThatThrownBy(() -> parser.dateTimes(value, "접수"))
                .isInstanceOf(LhAnnouncementEnrichmentRejectedException.class);
    }

    @Test
    void 단일_날짜_파서는_기간에서_앞_날짜만_가져오지_않는다() {
        assertThatThrownBy(() -> parser.dateTime("2026.09.14 ~ 2026.09.15", "접수"))
                .isInstanceOf(LhAnnouncementEnrichmentRejectedException.class);
    }
}
