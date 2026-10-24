package com.toadzip.backend.ingest.location.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

import com.toadzip.backend.ingest.location.domain.JusoAddress;
import com.toadzip.backend.ingest.location.exception.AddressLookupException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class JusoAddressClientTest {

    private MockRestServiceServer server;
    private RestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://business.juso.go.kr");
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
    }

    @Test
    void 검색_결과에서_PNU와_좌표_조회에_필요한_식별값을_읽는다() {
        server.expect(requestTo(startsWith("https://business.juso.go.kr/addrlink/addrLinkApi.do")))
                .andExpect(queryParam("confmKey", "search-key")).andExpect(queryParam("resultType", "json"))
                .andRespond(withSuccess("""
                        {"results":{"common":{"errorCode":"0","errorMessage":"정상"},"juso":[{
                          "roadAddr":"인천광역시 부평구 부평대로 1","jibunAddr":"인천광역시 부평구 부평동 65-17",
                          "admCd":"2823710100","rnMgtSn":"282373154001","udrtYn":"0","buldMnnm":"1","buldSlno":"0",
                          "mtYn":"0","lnbrMnnm":"65","lnbrSlno":"17"}]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("https://business.juso.go.kr/addrlink/addrCoordApi.do")))
                .andExpect(queryParam("confmKey", "coordinate-key")).andExpect(queryParam("admCd", "2823710100"))
                .andExpect(queryParam("buldMnnm", "1"))
                .andRespond(withSuccess("""
                        {"results":{"common":{"errorCode":"0"},"juso":[{"entX":"937500.123","entY":"1943000.456"}]}}
                        """, MediaType.APPLICATION_JSON));
        var juso = new JusoAddressClient(client, "search-key", "coordinate-key");

        JusoAddress address = juso.search("부평동 65-17", 5).getFirst();

        assertThat(address.pnu()).isEqualTo("2823710100100650017");
        assertThat(juso.coordinate(address)).hasValueSatisfying(coordinate -> {
            assertThat(coordinate.x()).isEqualByComparingTo(new BigDecimal("937500.123"));
            assertThat(coordinate.y()).isEqualByComparingTo(new BigDecimal("1943000.456"));
        });
        server.verify();
    }

    @Test
    void 키가_없거나_승인되지_않으면_사용할_수_없음으로_알린다() {
        assertThatThrownBy(() -> new JusoAddressClient(client, " ", "coordinate-key").search("부평동", 5))
                .isInstanceOfSatisfying(AddressLookupException.class, exception -> assertThat(exception.isUnavailable()).isTrue());
        server.expect(requestTo(startsWith("https://business.juso.go.kr/addrlink/addrLinkApi.do")))
                .andRespond(withSuccess("""
                        {"results":{"common":{"errorCode":"E0001","errorMessage":"승인되지 않은 KEY 입니다."},"juso":null}}
                        """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> new JusoAddressClient(client, "search-key", "coordinate-key").search("부평동", 5))
                .isInstanceOfSatisfying(AddressLookupException.class, exception -> assertThat(exception.isUnavailable()).isTrue());
    }

    @Test
    void 검색어_오류는_입력_문제로_구분하고_서버_오류는_사용할_수_없음으로_알린다() {
        server.expect(requestTo(startsWith("https://business.juso.go.kr/addrlink/addrLinkApi.do")))
                .andRespond(withSuccess("""
                        {"results":{"common":{"errorCode":"E0006","errorMessage":"주소를 상세히 입력해 주시기 바랍니다."}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("https://business.juso.go.kr/addrlink/addrLinkApi.do")))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        var juso = new JusoAddressClient(client, "search-key", "coordinate-key");

        assertThatThrownBy(() -> juso.search("인천", 5)).isInstanceOfSatisfying(AddressLookupException.class, exception -> {
            assertThat(exception.isUnavailable()).isFalse();
            assertThat(exception.getMessage()).isEqualTo("주소를 상세히 입력해 주시기 바랍니다.");
        });
        assertThatThrownBy(() -> juso.search("부평동", 5))
                .isInstanceOfSatisfying(AddressLookupException.class, exception -> assertThat(exception.isUnavailable()).isTrue());
    }

    @Test
    void 좌표가_없는_주소는_빈_값으로_돌려준다() {
        server.expect(requestTo(startsWith("https://business.juso.go.kr/addrlink/addrCoordApi.do")))
                .andRespond(withSuccess("""
                        {"results":{"common":{"errorCode":"0"},"juso":[]}}
                        """, MediaType.APPLICATION_JSON));
        var address = new JusoAddress("", "", "2823710100", "", false, 0, 0, false, 65, 17);

        assertThat(new JusoAddressClient(client, "search-key", "coordinate-key").coordinate(address)).isEmpty();
    }
}
