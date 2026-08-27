package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.domain.JusoAddressCode;
import com.toadzip.backend.ingest.domain.RoadAddressCandidate;
import com.toadzip.backend.ingest.domain.UtmKCoordinate;
import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason;
import com.toadzip.backend.ingest.repository.RoadAddressCoordinateRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoadAddressGeocodingServiceTest {

    private FakeRoadAddressCoordinateRepository repository;

    private RoadAddressGeocodingService service;

    @BeforeEach
    void setUp() {
        repository = new FakeRoadAddressCoordinateRepository();
        service = new RoadAddressGeocodingService(repository, new UtmKCoordinateConverter());
    }

    @Test
    void 도로명주소를_정제하고_WGS84_좌표로_변환한다() {
        repository.candidates = List.of(cityHall());
        repository.coordinate = Optional.of(new UtmKCoordinate(
                new BigDecimal("953875.0441724667"),
                new BigDecimal("1951999.4987320001")
        ));

        var result = service.geocode("  서울특별시  중구 세종대로 110 (태평로1가)  ");

        assertThat(result.roadAddress()).isEqualTo("서울특별시 중구 세종대로 110");
        assertThat(result.latitude()).isEqualByComparingTo("37.56620502");
        assertThat(result.longitude()).isEqualByComparingTo("126.97770628");
        assertThat(repository.searchedAddress).isEqualTo("서울특별시 중구 세종대로 110");
    }

    @Test
    void 검색_후_원본과_정확히_일치하는_주소만_선택한다() {
        repository.candidates = List.of(
                candidate("서울특별시 중구 세종대로14길 20-2"),
                cityHall()
        );
        repository.coordinate = Optional.of(new UtmKCoordinate(
                new BigDecimal("953875.0441724667"),
                new BigDecimal("1951999.4987320001")
        ));

        var result = service.geocode("서울특별시 중구 세종대로 110");

        assertThat(result.roadAddress()).isEqualTo("서울특별시 중구 세종대로 110");
        assertThat(repository.requestedCode).isEqualTo(cityHall().addressCode());
    }

    @Test
    void 원본과_일치하는_주소가_없으면_실패한다() {
        repository.candidates = List.of(candidate("서울특별시 중구 세종대로14길 20-2"));

        assertThatThrownBy(() -> service.geocode("서울특별시 중구 세종대로 110"))
                .isInstanceOfSatisfying(
                        RoadAddressGeocodingException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(RoadAddressGeocodingFailureReason.ADDRESS_NOT_FOUND)
                );
    }

    @Test
    void 원본과_일치하는_주소가_여러_건이면_실패한다() {
        repository.candidates = List.of(cityHall(), cityHall());

        assertThatThrownBy(() -> service.geocode("서울특별시 중구 세종대로 110"))
                .isInstanceOfSatisfying(
                        RoadAddressGeocodingException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(RoadAddressGeocodingFailureReason.AMBIGUOUS_ADDRESS)
                );
    }

    @Test
    void 좌표가_제공되지_않으면_실패한다() {
        repository.candidates = List.of(cityHall());
        repository.coordinate = Optional.empty();

        assertThatThrownBy(() -> service.geocode("서울특별시 중구 세종대로 110"))
                .isInstanceOfSatisfying(
                        RoadAddressGeocodingException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(RoadAddressGeocodingFailureReason.COORDINATE_NOT_FOUND)
                );
    }

    @Test
    void 비어_있는_도로명주소는_외부_API를_호출하지_않고_실패한다() {
        assertThatThrownBy(() -> service.geocode("  "))
                .isInstanceOfSatisfying(
                        RoadAddressGeocodingException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(RoadAddressGeocodingFailureReason.INVALID_ADDRESS)
                );
        assertThat(repository.searchCount).isZero();
    }

    @Test
    void 같은_주소를_반복하면_외부_API를_다시_호출하지_않는다() {
        repository.candidates = List.of(cityHall());
        repository.coordinate = Optional.of(new UtmKCoordinate(
                new BigDecimal("953875.0441724667"),
                new BigDecimal("1951999.4987320001")
        ));

        service.geocode("서울특별시 중구 세종대로 110");
        service.geocode("서울특별시 중구 세종대로 110 (태평로1가)");

        assertThat(repository.searchCount).isOne();
        assertThat(repository.coordinateCount).isOne();
    }

    private RoadAddressCandidate cityHall() {
        return new RoadAddressCandidate(
                "서울특별시 중구 세종대로 110 (태평로1가)",
                "서울특별시 중구 세종대로 110",
                new JusoAddressCode("1114010300", "111402005001", "0", "110", "0")
        );
    }

    private RoadAddressCandidate candidate(String roadAddress) {
        return new RoadAddressCandidate(
                roadAddress,
                roadAddress,
                new JusoAddressCode("1114011300", "111404103189", "0", "20", "2")
        );
    }

    private static class FakeRoadAddressCoordinateRepository implements RoadAddressCoordinateRepository {

        private List<RoadAddressCandidate> candidates = List.of();

        private Optional<UtmKCoordinate> coordinate = Optional.empty();

        private String searchedAddress;

        private JusoAddressCode requestedCode;

        private int searchCount;

        private int coordinateCount;

        @Override
        public List<RoadAddressCandidate> search(String roadAddress) {
            searchedAddress = roadAddress;
            searchCount++;
            return candidates;
        }

        @Override
        public Optional<UtmKCoordinate> findCoordinate(JusoAddressCode addressCode) {
            requestedCode = addressCode;
            coordinateCount++;
            return coordinate;
        }
    }
}
