package com.toadzip.backend.ingest.location.service;

import static com.toadzip.backend.ingest.location.service.LocationSummaryZipFixture.nationwideZip;
import static com.toadzip.backend.ingest.location.service.LocationSummaryZipFixture.partialZip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeComplexSourceFixtures;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSource;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.location.repository.RoadAddressLocationRepository;
import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LocationSummaryImportIntegrationTest {

    @Autowired
    private LocationSummaryImportService service;

    @Autowired
    private MyHomeComplexSourceFixtures sourceRepository;

    @Autowired
    private RoadAddressLocationRepository locationRepository;

    @Autowired
    private RoadAddressGeocodingService geocodingService;

    @BeforeEach
    void setUp() {
        locationRepository.deleteAll();
        sourceRepository.deleteAll();
        sourceRepository.save(source("서울특별시 종로구 테스트로 1 (테스트동)"));
    }

    @Test
    void 전국_전체분을_스캔하고_단지_주소와_일치한_행만_적재한다() throws IOException {
        var report = service.importMatches("202607_위치정보요약DB_전체분.zip", nationwideZip());

        assertThat(report.scannedRowCount()).isEqualTo(16);
        assertThat(report.targetRoadAddressCount()).isOne();
        assertThat(report.matchedRoadAddressCount()).isOne();
        assertThat(report.unmatchedRoadAddressCount()).isZero();
        assertThat(report.storedLocationCount()).isOne();
        assertThat(locationRepository.count()).isOne();
        assertThat(geocodingService.geocode("서울특별시 종로구 테스트로 1")).satisfies(result -> {
            assertThat(result.roadAddress()).isEqualTo("서울특별시 종로구 테스트로 1");
            assertThat(result.latitude()).isBetween(new BigDecimal("30"), new BigDecimal("40"));
            assertThat(result.longitude()).isBetween(new BigDecimal("120"), new BigDecimal("130"));
        });
    }

    @Test
    void 일부_시도만_있는_ZIP은_거절하고_기존_선별데이터를_유지한다() throws IOException {
        service.importMatches("full.zip", nationwideZip());

        assertThatThrownBy(() -> service.importMatches("partial.zip", partialZip()))
                .isInstanceOf(InvalidIngestRequestException.class)
                .hasMessageContaining("전국 월전체분이 아닙니다");
        assertThat(locationRepository.count()).isOne();
        assertThat(geocodingService.geocode("서울특별시 종로구 테스트로 1").roadAddress())
                .isEqualTo("서울특별시 종로구 테스트로 1");
    }

    @Test
    void 같은_전국_ZIP을_반복_적재하면_기존_행을_안전하게_교체한다() throws IOException {
        service.importMatches("first.zip", nationwideZip());

        var report = service.importMatches("second.zip", nationwideZip());

        assertThat(report.replacedRowCount()).isOne();
        assertThat(report.storedLocationCount()).isOne();
        assertThat(locationRepository.count()).isOne();
    }

    private MyHomeComplexSource source(String roadAddress) {
        return MyHomeComplexSource.from(new MyHomeComplexSourceSnapshot(
                1L, "한국토지주택공사", "11", "서울특별시", "110", "종로구", "테스트 단지",
                roadAddress, "1111010100100010000", "20200101", 100, "국민임대", "46A",
                new BigDecimal("46.8"), new BigDecimal("20.2"), "아파트", "지역난방", "복도식",
                "전체동 설치", 80, 10_000_000L, 200_000L, 20_000_000L
        ));
    }

}
