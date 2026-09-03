package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.domain.MyHomeComplexSourceData;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.repository.MyHomeComplexMappingCandidateRepository;
import com.toadzip.backend.ingest.repository.MyHomeComplexSourceRepository;
import com.toadzip.backend.ingest.repository.RoadAddressLocationRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LocationSummaryImportServiceTest {

    private static final Charset MS949 = Charset.forName("MS949");

    private static final Map<String, Province> ENTRIES = entries();

    @Autowired
    private LocationSummaryImportService service;

    @Autowired
    private MyHomeComplexSourceRepository sourceRepository;

    @Autowired
    private MyHomeComplexMappingCandidateRepository candidateRepository;

    @Autowired
    private RoadAddressLocationRepository locationRepository;

    @Autowired
    private RoadAddressGeocodingService geocodingService;

    @BeforeEach
    void setUp() {
        candidateRepository.deleteAll();
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

        assertThatThrownBy(() -> service.importMatches("partial.zip", zip(Map.of(
                "entrc_seoul.txt", new Province("11", "서울특별시")
        )))).isInstanceOf(InvalidIngestRequestException.class)
                .hasMessageContaining("전국 월전체분이 아닙니다");
        assertThat(locationRepository.count()).isOne();
        assertThat(geocodingService.geocode("서울특별시 종로구 테스트로 1").roadAddress())
                .isEqualTo("서울특별시 종로구 테스트로 1");
    }

    private ByteArrayInputStream nationwideZip() throws IOException {
        return zip(ENTRIES);
    }

    private ByteArrayInputStream zip(Map<String, Province> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, Province> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(row(entry.getValue().code(), entry.getValue().name()).getBytes(MS949));
                zip.closeEntry();
            }
        }
        return new ByteArrayInputStream(output.toByteArray());
    }

    private String row(String provinceCode, String provinceName) {
        String districtCode = provinceCode.equals("11") ? "11110" : provinceCode + "000";
        String districtName = provinceCode.equals("11") ? "종로구" : "테스트시";
        String roadName = provinceCode.equals("11") ? "테스트로" : "비대상로";
        return String.join("|",
                districtCode,
                "1",
                districtCode + "00000",
                provinceName,
                districtName,
                "테스트동",
                districtCode + "0000001",
                roadName,
                "0",
                "1",
                "0",
                "건물",
                "12345",
                "시설",
                "0",
                "행정동",
                "953875.044172",
                "1951999.498732"
        );
    }

    private MyHomeComplexSource source(String roadAddress) {
        return MyHomeComplexSource.from(new MyHomeComplexSourceData(
                1L, "한국토지주택공사", "11", "서울특별시", "110", "종로구", "테스트 단지",
                roadAddress, "1111010100100010000", "20200101", 100, "국민임대", "46A",
                new BigDecimal("46.8"), new BigDecimal("20.2"), "아파트", "지역난방", "복도식",
                "전체동 설치", 80, 10_000_000L, 200_000L, 20_000_000L
        ));
    }

    private static Map<String, Province> entries() {
        Map<String, Province> entries = new LinkedHashMap<>();
        entries.put("entrc_busan.txt", new Province("26", "부산광역시"));
        entries.put("entrc_chungbuk.txt", new Province("43", "충청북도"));
        entries.put("entrc_chungnam.txt", new Province("44", "충청남도"));
        entries.put("entrc_daegu.txt", new Province("27", "대구광역시"));
        entries.put("entrc_daejeon.txt", new Province("30", "대전광역시"));
        entries.put("entrc_gangwon.txt", new Province("51", "강원특별자치도"));
        entries.put("entrc_gyeongbuk.txt", new Province("47", "경상북도"));
        entries.put("entrc_gyeongnam.txt", new Province("48", "경상남도"));
        entries.put("entrc_gyunggi.txt", new Province("41", "경기도"));
        entries.put("entrc_incheon.txt", new Province("28", "인천광역시"));
        entries.put("entrc_jeju.txt", new Province("50", "제주특별자치도"));
        entries.put("entrc_jeonbuk.txt", new Province("52", "전북특별자치도"));
        entries.put("entrc_jeonnamgwangju.txt", new Province("12", "광주전남"));
        entries.put("entrc_sejong.txt", new Province("36", "세종특별자치시"));
        entries.put("entrc_seoul.txt", new Province("11", "서울특별시"));
        entries.put("entrc_ulsan.txt", new Province("31", "울산광역시"));
        return entries;
    }

    private record Province(String code, String name) {
    }
}
