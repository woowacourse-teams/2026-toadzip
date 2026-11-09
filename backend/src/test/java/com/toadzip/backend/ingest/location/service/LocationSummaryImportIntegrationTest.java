package com.toadzip.backend.ingest.location.service;

import static com.toadzip.backend.ingest.location.service.LocationSummaryZipFixture.nationwideZip;
import static com.toadzip.backend.ingest.location.service.LocationSummaryZipFixture.partialZip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.fixture.MyHomeSourceViews;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeComplexSourceFixtures;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.location.repository.RoadAddressLocationRepository;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationLocationRequest;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationTargetRepository;
import com.toadzip.backend.ingest.mapping.service.MyHomeInitialRegistrationService;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ExtendWith(IngestExecutionExtension.class)
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

    @Autowired
    private MyHomeInitialRegistrationService registration;

    @Autowired
    private InitialRegistrationTargetRepository targets;

    @BeforeEach
    void setUp() {
        locationRepository.deleteAll();
        sourceRepository.deleteAll();
        targets.deleteAll();
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
    void 주소_일치와_좌표_준비를_구분하고_누락_주소도_보완_목록에_집계한다() throws IOException {
        sourceRepository.deleteAll();
        sourceRepository.save(source(null));
        service.importMatches("full.zip", nationwideZip());
        var missing = registration.findLocations(0, 20);
        assertThat(missing.counts()).containsEntry("INVALID_ADDRESS", 1L);
        assertThat(missing.targets()).singleElement().satisfies(target -> assertThat(target.roadAddress()).isNull());

        sourceRepository.deleteAll();
        sourceRepository.save(source("서울특별시 종로구 테스트로 1"));
        var report = service.importMatches("no-coordinates.zip",
                LocationSummaryZipFixture.nationwideZipWithCoordinates("", ""));
        assertThat(report.matchedRoadAddressCount()).isOne();
        assertThat(report.preparedRoadAddressCount()).isZero();
        assertThat(registration.findLocations(0, 20).counts()).containsEntry("COORDINATE_NOT_FOUND", 1L);
    }

    @Test
    void 주소가_전혀_일치하지_않아도_단지별_실패_목록에서_원인을_확인한다() throws IOException {
        var report = service.importMatches("wrong-addresses.zip",
                LocationSummaryZipFixture.nationwideZipWithoutTargetAddress());
        assertThat(report.matchedRoadAddressCount()).isZero();
        assertThat(report.preparedRoadAddressCount()).isZero();
        assertThat(registration.findLocations(0, 20).counts()).containsEntry("ADDRESS_NOT_FOUND", 1L);
    }

    @Test
    void 원본_좌표의_변환이_실패하면_주소_일치와_구분해_보완_사유를_저장한다() throws IOException {
        var report = service.importMatches("bad-coordinates.zip",
                LocationSummaryZipFixture.nationwideZipWithCoordinates("999999999", "999999999"));
        assertThat(report.matchedRoadAddressCount()).isOne();
        assertThat(report.preparedRoadAddressCount()).isZero();
        assertThat(registration.findLocations(0, 20).counts()).containsEntry("COORDINATE_CONVERSION_ERROR", 1L);
    }

    @Test
    void 좌표_없는_ZIP과_주소_불일치_ZIP을_올려도_기존_정상_좌표를_보존한다() throws IOException {
        service.importMatches("good.zip", nationwideZip());
        var before = geocodingService.geocode("서울특별시 종로구 테스트로 1");
        service.importMatches("no-coordinates.zip", LocationSummaryZipFixture.nationwideZipWithCoordinates("", ""));
        service.importMatches("wrong-addresses.zip", LocationSummaryZipFixture.nationwideZipWithoutTargetAddress());
        assertThat(geocodingService.geocode("서울특별시 종로구 테스트로 1")).isEqualTo(before);
        assertThat(registration.findLocations(0, 20).counts()).containsEntry("READY", 1L);
    }

    @Test
    void 주소가_없는_단지를_보완하고_ZIP_재업로드와_상태_재확인에도_관리자_좌표를_보존한다() throws IOException {
        sourceRepository.deleteAll();
        sourceRepository.save(source(null));
        registration.refresh();
        String identifier = targets.findAll().getFirst().getSourceComplexIdentifier();
        registration.supplementLocation(new InitialRegistrationLocationRequest(identifier,
                "서울특별시 종로구 보완로 2", new BigDecimal("37.6"), new BigDecimal("127.1")), "test-admin");
        service.importMatches("full.zip", nationwideZip());
        registration.refresh();
        var restored = targets.findById(identifier).orElseThrow();
        assertThat(restored.getRoadAddress()).isNull();
        assertThat(restored.getStatus()).isEqualTo(InitialRegistrationStatus.READY);
        assertThat(restored.getLocation().getAddress()).isEqualTo("서울특별시 종로구 보완로 2");
        assertThat(restored.getLocation().getOrigin()).isEqualTo("ADMIN");
        assertThat(restored.getLocation().getCheckedBy()).isEqualTo("test-admin");
        assertThat(registration.findLocations(0, 20).totalElements()).isZero();
    }

    @Test
    void 원천이_사라지면_보완_목록과_집계에서_제외하고_재수집하면_다시_포함한다() {
        registration.refresh();
        String identifier = targets.findAll().getFirst().getSourceComplexIdentifier();

        sourceRepository.deleteAll();
        registration.refresh();

        var missing = registration.findLocations(0, 20);
        assertThat(missing.targets()).isEmpty();
        assertThat(missing.totalElements()).isZero();
        assertThat(missing.totalPages()).isZero();
        assertThat(missing.counts().values()).allMatch(count -> count == 0L);
        var retained = targets.findById(identifier).orElseThrow();
        assertThat(retained.getStatus()).isEqualTo(InitialRegistrationStatus.INFORMATION_REQUIRED);
        assertThat(retained.getDetail()).contains("현재 수집 원천에서 찾을 수 없습니다");
        assertThat(retained.getLocation().getFailure()).isNotNull();

        sourceRepository.save(source("서울특별시 종로구 테스트로 1 (테스트동)"));
        registration.refresh();

        var recollected = registration.findLocations(0, 20);
        assertThat(recollected.targets()).singleElement()
                .satisfies(target -> assertThat(target.sourceComplexIdentifier()).isEqualTo(identifier));
        assertThat(recollected.counts()).containsEntry("ADDRESS_NOT_FOUND", 1L);
    }

    @Test
    void 원천이_사라지면_준비_완료_집계에서도_제외하고_재수집하면_기존_관리자_보완값을_사용한다() {
        registration.refresh();
        String identifier = targets.findAll().getFirst().getSourceComplexIdentifier();
        registration.supplementLocation(new InitialRegistrationLocationRequest(identifier,
                "서울특별시 종로구 보완로 2", new BigDecimal("37.6"), new BigDecimal("127.1")), "test-admin");

        sourceRepository.deleteAll();
        registration.refresh();

        assertThat(registration.findLocations(0, 20).counts()).containsEntry("READY", 0L);
        var retained = targets.findById(identifier).orElseThrow();
        assertThat(retained.getStatus()).isEqualTo(InitialRegistrationStatus.INFORMATION_REQUIRED);
        assertThat(retained.getLocation().getAddress()).isEqualTo("서울특별시 종로구 보완로 2");
        assertThat(retained.getLocation().getLatitude()).isEqualByComparingTo("37.6");
        assertThat(retained.getLocation().getLongitude()).isEqualByComparingTo("127.1");
        assertThat(retained.getLocation().getCheckedBy()).isEqualTo("test-admin");

        sourceRepository.save(source("서울특별시 종로구 테스트로 1 (테스트동)"));
        registration.refresh();

        assertThat(registration.findLocations(0, 20).counts()).containsEntry("READY", 1L);
        var restored = targets.findById(identifier).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(InitialRegistrationStatus.READY);
        assertThat(restored.getLocation().getAddress()).isEqualTo("서울특별시 종로구 보완로 2");
        assertThat(restored.getLocation().getOrigin()).isEqualTo("ADMIN");
        assertThat(restored.getLocation().getCheckedBy()).isEqualTo("test-admin");
    }

    @Test
    void 같은_전국_ZIP을_반복_적재하면_기존_행을_안전하게_교체한다() throws IOException {
        service.importMatches("first.zip", nationwideZip());

        var report = service.importMatches("second.zip", nationwideZip());

        assertThat(report.replacedRowCount()).isOne();
        assertThat(report.storedLocationCount()).isOne();
        assertThat(locationRepository.count()).isOne();
    }

    private MyHomeComplexSourceView source(String roadAddress) {
        return MyHomeSourceViews.complex(new MyHomeComplexSourceSnapshot(
                1L, "한국토지주택공사", "11", "서울특별시", "110", "종로구", "테스트 단지",
                roadAddress, "1111010100100010000", "20200101", 100, "국민임대", "46A",
                new BigDecimal("46.8"), new BigDecimal("20.2"), "아파트", "지역난방", "복도식",
                "전체동 설치", 80, 10_000_000L, 200_000L, 20_000_000L
        ));
    }

}
