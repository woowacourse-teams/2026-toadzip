package com.toadzip.backend.search.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.toadzip.backend.search.domain.LocationSearchType;
import com.toadzip.backend.search.exception.LocationSearchUnavailableException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KakaoLocationSearchRepositoryTest {

    private MockRestServiceServer server;
    private KakaoLocationSearchRepository repository;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        server = MockRestServiceServer.bindTo(builder).build();
        repository = new KakaoLocationSearchRepository(builder.build(), "test-key");
    }

    @Test
    void 읍면동_지명만_반환하고_번지_좌표를_지역_좌표로_사용하지_않는다() {
        server.expect(request -> assertThat(request.getURI().getPath()).endsWith("address.json"))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess("""
                        {"meta":{"is_end":true,"pageable_count":2},"documents":[
                          {"address_type":"REGION","address_name":"서울 강남구 역삼동","x":"127.03","y":"37.5"},
                          {"address_type":"REGION_ADDR","address_name":"서울 강남구 역삼동 1","x":"127","y":"37"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        LocationSearchPage result = repository.search("역삼동", LocationSearchType.REGION, 0, 5);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("서울 강남구 역삼동");
        assertThat(result.items().getFirst().coordinate().longitude()).isEqualByComparingTo("127.03");
        assertThat(result.totalCount()).isNull();
        assertThat(result.hasNext()).isFalse();
        server.verify();
    }

    @Test
    void 장소_검색은_이름과_종류_주소_좌표를_제공하고_페이지를_일부터_전달한다() {
        server.expect(request -> {
            assertThat(request.getURI().getPath()).endsWith("keyword.json");
            assertThat(request.getURI().getRawQuery()).contains("page=2", "size=5", "%2B", "%26");
        }).andRespond(withSuccess(places(false, 12, "127.03"), MediaType.APPLICATION_JSON));

        LocationSearchPage result = repository.search("서울역 + &", LocationSearchType.PLACE, 1, 5);

        assertThat(result.items().getFirst().title()).isEqualTo("서울역");
        assertThat(result.items().getFirst().subtitle()).contains("지하철역", "서울 중구 세종대로 1");
        assertThat(result.items().getFirst().coordinate().latitude()).isEqualTo(new BigDecimal("37.5"));
        assertThat(result.totalCount()).isEqualTo(12L);
        assertThat(result.hasNext()).isTrue();
        server.verify();
    }

    @Test
    void 장소_더보기는_제공자의_최대_45개_이후로_요청하지_않는다() {
        server.expect(request -> { }).andRespond(withSuccess(places(false, 45, "127.03"),
                MediaType.APPLICATION_JSON));

        assertThat(repository.search("서울역", LocationSearchType.PLACE, 8, 5).hasNext()).isFalse();
        assertThat(repository.search("서울역", LocationSearchType.PLACE, 9, 5).items()).isEmpty();
        server.verify();
    }

    @Test
    void 빈_결과는_성공한_빈_마지막_페이지다() {
        server.expect(request -> { }).andRespond(withSuccess("""
                {"meta":{"is_end":true,"pageable_count":0},"documents":[]}
                """, MediaType.APPLICATION_JSON));

        LocationSearchPage result = repository.search("없는장소", LocationSearchType.PLACE, 0, 5);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalCount()).isZero();
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void 키_미설정은_외부_요청_없이_명시적으로_실패한다() {
        KakaoLocationSearchRepository unconfigured = new KakaoLocationSearchRepository(RestClient.create(), " ");

        assertThatThrownBy(() -> unconfigured.search("서울역", LocationSearchType.PLACE, 0, 5))
                .isInstanceOf(LocationSearchUnavailableException.class);
    }

    @Test
    void 외부_인증_실패는_빈_성공으로_위장하거나_응답_원문을_노출하지_않는다() {
        server.expect(request -> { }).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .body("sensitive upstream body"));

        assertThatThrownBy(() -> repository.search("서울역", LocationSearchType.PLACE, 0, 5))
                .isInstanceOf(LocationSearchUnavailableException.class)
                .hasMessageNotContaining("sensitive");
    }

    @Test
    void 잘못된_좌표는_지도에서_사용하지_않고_외부_오류로_처리한다() {
        server.expect(request -> { }).andRespond(withSuccess(places(true, 1, "181"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> repository.search("서울역", LocationSearchType.PLACE, 0, 5))
                .isInstanceOf(LocationSearchUnavailableException.class);
    }

    @Test
    void 잘못된_응답_구조를_빈_성공으로_처리하지_않는다() {
        server.expect(request -> { }).andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> repository.search("서울역", LocationSearchType.PLACE, 0, 5))
                .isInstanceOf(LocationSearchUnavailableException.class);
    }

    private String places(boolean last, int count, String longitude) {
        return """
                {"meta":{"is_end":%s,"pageable_count":%d},"documents":[{
                  "id":"123","place_name":"서울역","category_name":"교통 > 지하철역",
                  "address_name":"서울 중구 봉래동","road_address_name":"서울 중구 세종대로 1",
                  "x":"%s","y":"37.5"
                }]}
                """.formatted(last, count, longitude);
    }
}
