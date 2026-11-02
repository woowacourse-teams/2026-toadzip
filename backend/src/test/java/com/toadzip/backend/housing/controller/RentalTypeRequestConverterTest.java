package com.toadzip.backend.housing.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.housing.domain.RentalType;
import org.junit.jupiter.api.Test;

class RentalTypeRequestConverterTest {

    private final RentalTypeRequestConverter converter = new RentalTypeRequestConverter();

    @Test
    void 빈_값은_필터_없음으로_두고_합치기_전_코드는_공공임대로_읽는다() {
        assertThat(converter.convert("")).isNull();
        assertThat(converter.convert(" ")).isNull();
        assertThat(converter.convert(" PUBLIC_RENTAL_10Y ")).isEqualTo(RentalType.PUBLIC_RENTAL);
    }
}
