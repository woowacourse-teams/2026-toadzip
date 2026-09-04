package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.domain.LocationSummaryRecord;
import com.toadzip.backend.ingest.domain.RoadAddressLocation;
import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason;
import com.toadzip.backend.ingest.repository.RoadAddressLocationRepository;
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
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(RoadAddressGeocodingFailureReason.ADDRESS_NOT_FOUND)
                );
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
        BigDecimal x = withCoordinate ? new BigDecimal("953875.0441724667") : null;
        BigDecimal y = withCoordinate ? new BigDecimal("1951999.4987320001") : null;
        return RoadAddressLocation.from(new LocationSummaryRecord(
                "11140", "1", "1114010300", "서울특별시", "중구", "태평로1가",
                "111402005001", "세종대로", "0", 110, 0, x, y
        ));
    }
}
