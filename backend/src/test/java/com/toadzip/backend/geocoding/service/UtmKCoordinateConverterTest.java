package com.toadzip.backend.geocoding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.geocoding.domain.UtmKCoordinate;
import com.toadzip.backend.geocoding.exception.RoadAddressGeocodingException;
import com.toadzip.backend.geocoding.exception.RoadAddressGeocodingFailureReason;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UtmKCoordinateConverterTest {

    private final UtmKCoordinateConverter converter = new UtmKCoordinateConverter();

    @Test
    void 서울시청_UTM_K_좌표를_WGS84로_변환한다() {
        var result = converter.convert(new UtmKCoordinate(
                new BigDecimal("953875.0441724667"),
                new BigDecimal("1951999.4987320001")
        ));

        assertThat(result.latitude()).isEqualByComparingTo("37.56620502");
        assertThat(result.longitude()).isEqualByComparingTo("126.97770628");
    }

    @Test
    void 변환할_수_없는_좌표는_명확한_실패로_반환한다() {
        assertThatThrownBy(() -> converter.convert(new UtmKCoordinate(
                new BigDecimal("1E+1000"),
                new BigDecimal("1E+1000")
        )))
                .isInstanceOfSatisfying(
                        RoadAddressGeocodingException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(RoadAddressGeocodingFailureReason.COORDINATE_CONVERSION_ERROR)
                );
    }
}
