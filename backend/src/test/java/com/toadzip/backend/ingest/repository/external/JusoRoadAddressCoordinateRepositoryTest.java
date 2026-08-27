package com.toadzip.backend.ingest.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.toadzip.backend.ingest.configuration.JusoGeocodingProperties;
import com.toadzip.backend.ingest.domain.JusoAddressCode;
import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class JusoRoadAddressCoordinateRepositoryTest {

    private MockRestServiceServer server;

    private JusoRoadAddressCoordinateRepository repository;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        AtomicLong nanoTime = new AtomicLong();
        JusoCoordinateRequestRateLimiter limiter = new JusoCoordinateRequestRateLimiter(
                nanoTime::get,
                duration -> nanoTime.addAndGet(duration.toNanos())
        );
        repository = new JusoRoadAddressCoordinateRepository(
                builder.build(),
                new JusoGeocodingProperties("https://example.com", "address-key", "coordinate-key"),
                limiter
        );
    }

    @Test
    void 도로명주소를_POST로_검색하고_주소_코드를_반환한다() {
        server.expect(request -> {
            assertThat(request.getURI()).hasToString("https://example.com/addrLinkApi.do");
            assertThat(request.getMethod().name()).isEqualTo("POST");
            assertThat(request.getBody().toString()).contains("confmKey=address-key");
        }).andRespond(withSuccess(addressPayload(), MediaType.APPLICATION_JSON));

        var result = repository.search("서울특별시 중구 세종대로 110");

        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.roadAddressWithoutReference()).isEqualTo("서울특별시 중구 세종대로 110");
            assertThat(candidate.addressCode().roadNameCode()).isEqualTo("111402005001");
        });
        server.verify();
    }

    @Test
    void 좌표_코드로_UTM_K_좌표를_조회한다() {
        server.expect(request -> assertThat(request.getURI())
                .hasToString("https://example.com/addrCoordApi.do"))
                .andRespond(withSuccess(coordinatePayload(), MediaType.APPLICATION_JSON));

        var result = repository.findCoordinate(
                new JusoAddressCode("1114010300", "111402005001", "0", "110", "0")
        );

        assertThat(result).hasValueSatisfying(coordinate -> {
            assertThat(coordinate.x()).isEqualByComparingTo("953875.0441724667");
            assertThat(coordinate.y()).isEqualByComparingTo("1951999.4987320001");
        });
        server.verify();
    }

    @Test
    void 원천_오류코드를_외부_API_실패로_변환한다() {
        server.expect(request -> assertThat(request.getURI())
                .hasToString("https://example.com/addrLinkApi.do"))
                .andRespond(withSuccess(
                        "{\"results\":{\"common\":{\"errorCode\":\"E0001\","
                                + "\"errorMessage\":\"승인되지 않은 KEY 입니다.\"}}}",
                        MediaType.APPLICATION_JSON
                ));

        assertThatThrownBy(() -> repository.search("서울특별시 중구 세종대로 110"))
                .isInstanceOfSatisfying(
                        JusoApiException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(RoadAddressGeocodingFailureReason.EXTERNAL_API_ERROR)
                )
                .hasMessageNotContaining("address-key");
        server.verify();
    }

    @Test
    void 좌표값이_비어_있으면_좌표_없음으로_반환한다() {
        server.expect(request -> assertThat(request.getURI())
                .hasToString("https://example.com/addrCoordApi.do"))
                .andRespond(withSuccess(
                        "{\"results\":{\"common\":{\"errorCode\":\"0\"},"
                                + "\"juso\":[{\"entX\":\"\",\"entY\":\"\"}]}}",
                        MediaType.APPLICATION_JSON
                ));

        var result = repository.findCoordinate(
                new JusoAddressCode("1114010300", "111402005001", "0", "110", "0")
        );

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void 승인키가_없으면_외부_호출_전에_실패한다() {
        repository = new JusoRoadAddressCoordinateRepository(
                RestClient.create(),
                new JusoGeocodingProperties("https://example.com", "", ""),
                new JusoCoordinateRequestRateLimiter()
        );

        assertThatThrownBy(() -> repository.search("서울특별시 중구 세종대로 110"))
                .isInstanceOfSatisfying(
                        JusoApiException.class,
                        exception -> assertThat(exception.getReason())
                                .isEqualTo(RoadAddressGeocodingFailureReason.NOT_CONFIGURED)
                );
    }

    private String addressPayload() {
        return """
                {"results":{"common":{"errorCode":"0","errorMessage":"정상","totalCount":"1"},
                "juso":[{"roadAddr":"서울특별시 중구 세종대로 110 (태평로1가)",
                "roadAddrPart1":"서울특별시 중구 세종대로 110","admCd":"1114010300",
                "rnMgtSn":"111402005001","udrtYn":"0","buldMnnm":"110","buldSlno":"0"}]}}
                """;
    }

    private String coordinatePayload() {
        return """
                {"results":{"common":{"errorCode":"0","errorMessage":"정상","totalCount":"1"},
                "juso":[{"entX":"953875.0441724667","entY":"1951999.4987320001"}]}}
                """;
    }
}
