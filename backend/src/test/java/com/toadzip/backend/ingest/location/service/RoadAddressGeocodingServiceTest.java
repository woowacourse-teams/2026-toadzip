package com.toadzip.backend.ingest.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.location.domain.LocationSummaryRecord;
import com.toadzip.backend.ingest.location.domain.RoadAddressGeocodingFailureReason;
import com.toadzip.backend.ingest.location.domain.RoadAddressLocation;
import com.toadzip.backend.ingest.location.domain.UtmKCoordinate;
import com.toadzip.backend.ingest.location.domain.Wgs84Coordinate;
import com.toadzip.backend.ingest.location.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.location.repository.RoadAddressLocationRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoadAddressGeocodingServiceTest {

    private RoadAddressLocationRepository locationRepository;

    private RoadAddressGeocodingService service;

    @BeforeEach
    void setUp() {
        locationRepository = mock(RoadAddressLocationRepository.class);
        service = new RoadAddressGeocodingService(locationRepository, new UtmKCoordinateConverter());
    }

    @Test
    void 선별_적재된_위치정보요약DB_좌표를_WGS84로_변환한다() {
        when(locationRepository.findAllByNormalizedRoadAddressOrderByIdEntranceSerialAsc(
                "서울특별시 중구 세종대로 110"
        )).thenReturn(List.of(location(true)));

        var result = service.geocode("  서울특별시  중구 세종대로 110 (태평로1가)  ");

        assertThat(result.roadAddress()).isEqualTo("서울특별시 중구 세종대로 110");
        assertThat(result.latitude()).isEqualByComparingTo("37.56620502");
        assertThat(result.longitude()).isEqualByComparingTo("126.97770628");
    }

    @Test
    void 주소는_있지만_좌표가_비공개이면_실패한다() {
        when(locationRepository.findAllByNormalizedRoadAddressOrderByIdEntranceSerialAsc(
                "서울특별시 중구 세종대로 110"
        )).thenReturn(List.of(location(false)));

        assertThatThrownBy(() -> service.geocode("서울특별시 중구 세종대로 110"))
                .isInstanceOfSatisfying(
                        RoadAddressGeocodingException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(RoadAddressGeocodingFailureReason.COORDINATE_NOT_FOUND)
                );
    }

    @Test
    void 선별_적재된_주소가_없으면_실패한다() {
        when(locationRepository.findAllByNormalizedRoadAddressOrderByIdEntranceSerialAsc(
                "서울특별시 중구 세종대로 110"
        )).thenReturn(List.of());

        assertThatThrownBy(() -> service.geocode("서울특별시 중구 세종대로 110"))
                .isInstanceOfSatisfying(
                        RoadAddressGeocodingException.class,
                        exception -> {
                            assertThat(exception.getReason())
                                    .isEqualTo(RoadAddressGeocodingFailureReason.ADDRESS_NOT_FOUND);
                            assertThat(exception).hasMessageContaining("ZIP");
                        }
                );
    }

    @Test
    void 첫_출입구의_변환이_실패해도_다른_정상_출입구의_좌표를_사용한다() {
        var first = location(true);
        var second = RoadAddressLocation.from(new LocationSummaryRecord(
                "11140", "2", "1114010300", "서울특별시", "중구", "태평로1가",
                "111402005001", "세종대로", "0", 110, 0, BigDecimal.ONE, BigDecimal.TEN));
        var converter = mock(UtmKCoordinateConverter.class);
        when(locationRepository.findAllByNormalizedRoadAddressOrderByIdEntranceSerialAsc(
                "서울특별시 중구 세종대로 110")).thenReturn(List.of(first, second));
        when(converter.convert(first.coordinate().orElseThrow())).thenThrow(new RoadAddressGeocodingException(
                RoadAddressGeocodingFailureReason.COORDINATE_CONVERSION_ERROR, "변환 실패"));
        when(converter.convert(new UtmKCoordinate(BigDecimal.ONE, BigDecimal.TEN)))
                .thenReturn(new Wgs84Coordinate(new BigDecimal("37.5"), new BigDecimal("127")));
        var result = new RoadAddressGeocodingService(locationRepository, converter)
                .geocode("서울특별시 중구 세종대로 110");
        assertThat(result.latitude()).isEqualByComparingTo("37.5");
    }

    @Test
    void 도로명과_건물번호가_없는_주소는_형식_오류로_구분한다() {
        assertThatThrownBy(() -> service.geocode("주소 확인 필요"))
                .isInstanceOfSatisfying(RoadAddressGeocodingException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(RoadAddressGeocodingFailureReason.INVALID_ADDRESS));
    }

    @Test
    void 비어_있는_도로명주소는_저장소를_조회하지_않는다() {
        assertThatThrownBy(() -> service.geocode("  "))
                .isInstanceOfSatisfying(
                        RoadAddressGeocodingException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(RoadAddressGeocodingFailureReason.INVALID_ADDRESS)
                );
        verify(locationRepository, never())
                .findAllByNormalizedRoadAddressOrderByIdEntranceSerialAsc("  ");
    }

    private RoadAddressLocation location(boolean withCoordinate) {
        BigDecimal x = null;
        BigDecimal y = null;
        if (withCoordinate) {
            x = new BigDecimal("953875.0441724667");
            y = new BigDecimal("1951999.4987320001");
        }
        return RoadAddressLocation.from(new LocationSummaryRecord(
                "11140", "1", "1114010300", "서울특별시", "중구", "태평로1가",
                "111402005001", "세종대로", "0", 110, 0, x, y
        ));
    }
}
