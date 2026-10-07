package com.toadzip.backend.search.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.toadzip.backend.search.domain.LocationSearchType;
import com.toadzip.backend.search.exception.LocationSearchUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NaverLocationSearchRepositoryTest {

    private MockRestServiceServer searchServer;
    private MockRestServiceServer mapsServer;
    private RestClient searchClient;
    private RestClient mapsClient;
    private NaverLocationSearchRepository repository;

    @BeforeEach
    void setUp() {
        RestClient.Builder searchBuilder = RestClient.builder().baseUrl("https://openapi.naver.com");
        RestClient.Builder mapsBuilder = RestClient.builder().baseUrl("https://maps.apigw.ntruss.com");
        searchServer = MockRestServiceServer.bindTo(searchBuilder).build();
        mapsServer = MockRestServiceServer.bindTo(mapsBuilder).build();
        searchClient = searchBuilder.build();
        mapsClient = mapsBuilder.build();
        repository = new NaverLocationSearchRepository(searchClient, mapsClient,
                "search-id", "search-secret", "maps-id", "maps-key");
    }

    @Test
    void 장소_검색은_네이버_검색_계약과_WGS84_좌표를_사용한다() {
        searchServer.expect(request -> {
            assertThat(request.getURI().getHost()).isEqualTo("openapi.naver.com");
            assertThat(request.getURI().getPath()).isEqualTo("/v1/search/local.json");
            assertThat(request.getURI().getRawQuery()).contains("start=1", "display=5", "%2B", "%26");
        }).andExpect(header("X-Naver-Client-Id", "search-id"))
                .andExpect(header("X-Naver-Client-Secret", "search-secret"))
                .andRespond(withSuccess(places("\"1270300000\""), MediaType.APPLICATION_JSON));

        LocationSearchPage result = repository.search("서울역 + &", LocationSearchType.PLACE, 0, 5);

        assertThat(result.items().getFirst().title()).isEqualTo("서울역 & 기관");
        assertThat(result.items().getFirst().subtitle()).isEqualTo("교통>지하철역 · 서울 중구 세종대로 1");
        assertThat(result.items().getFirst().coordinate().latitude()).isEqualByComparingTo("37.5");
        assertThat(result.items().getFirst().coordinate().longitude()).isEqualByComparingTo("127.03");
        assertThat(result.totalCount()).isEqualTo(1L);
        assertThat(result.hasNext()).isFalse();
        searchServer.verify();
        mapsServer.verify();
    }

    @Test
    void 정수형_좌표도_해석하며_도로명_주소가_없으면_지번_주소를_사용한다() {
        searchServer.expect(request -> { }).andRespond(withSuccess(
                places("1270300000").replace("서울 중구 세종대로 1", ""), MediaType.APPLICATION_JSON));

        LocationSearchPage result = repository.search("서울역", LocationSearchType.PLACE, 0, 5);

        assertThat(result.items().getFirst().coordinate().longitude()).isEqualByComparingTo("127.03");
        assertThat(result.items().getFirst().subtitle()).endsWith("서울 중구 봉래동");
    }

    @Test
    void 장소는_최대_5개만_요청하고_다음_페이지에서_동일한_검색을_반복하지_않는다() {
        searchServer.expect(request -> assertThat(request.getURI().getRawQuery()).contains("display=5"))
                .andRespond(withSuccess(places("1270300000").replace("\"total\":1", "\"total\":25"),
                        MediaType.APPLICATION_JSON));

        LocationSearchPage first = repository.search("서울역", LocationSearchType.PLACE, 0, 15);
        LocationSearchPage next = repository.search("서울역", LocationSearchType.PLACE, 1, 15);

        assertThat(first.totalCount()).isEqualTo(5L);
        assertThat(first.hasNext()).isFalse();
        assertThat(next.items()).isEmpty();
        assertThat(next.hasNext()).isFalse();
        searchServer.verify();
    }

    @Test
    void 강조_위치가_달라도_같은_장소의_식별자는_유지된다() {
        searchServer.expect(request -> { }).andRespond(withSuccess(places("1270300000"), MediaType.APPLICATION_JSON));
        searchServer.expect(request -> { }).andRespond(withSuccess(
                places("1270300000").replace("<b>서울역</b>", "서울<b>역</b>"), MediaType.APPLICATION_JSON));

        String firstId = repository.search("서울역", LocationSearchType.PLACE, 0, 5).items().getFirst().id();
        String secondId = repository.search("역 기관", LocationSearchType.PLACE, 0, 5).items().getFirst().id();

        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void 읍면동은_Maps_주소_검색을_사용하고_번지나_건물_좌표를_제외한다() {
        mapsServer.expect(request -> {
            assertThat(request.getURI().getHost()).isEqualTo("maps.apigw.ntruss.com");
            assertThat(request.getURI().getPath()).isEqualTo("/map-geocode/v2/geocode");
            assertThat(request.getURI().getRawQuery()).contains("page=1", "count=5");
        }).andExpect(header("x-ncp-apigw-api-key-id", "maps-id"))
                .andExpect(header("x-ncp-apigw-api-key", "maps-key"))
                .andExpect(header("Accept", "application/json"))
                .andRespond(withSuccess(regions("", "1"), MediaType.APPLICATION_JSON));

        LocationSearchPage result = repository.search("역삼동", LocationSearchType.REGION, 0, 5);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("서울특별시 강남구 역삼동");
        assertThat(result.items().getFirst().coordinate().longitude()).isEqualByComparingTo("127.03");
        assertThat(result.totalCount()).isNull();
        assertThat(result.hasNext()).isFalse();
        searchServer.verify();
        mapsServer.verify();
    }

    @Test
    void 지역_페이지는_일부터_요청하고_후처리_전_전체_건수로_더보기를_계산한다() {
        mapsServer.expect(request -> assertThat(request.getURI().getRawQuery()).contains("page=2", "%2B", "%26"))
                .andRespond(withSuccess(regions("", "1").replace("\"totalCount\":2", "\"totalCount\":12"),
                        MediaType.APPLICATION_JSON));

        assertThat(repository.search("역삼동 + &", LocationSearchType.REGION, 1, 5).hasNext()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROAD_NAME", "BUILDING_NUMBER", "BUILDING_NAME", "LAND_NUMBER"})
    void 상세_주소_요소가_있으면_지역_대표_좌표로_사용하지_않는다(String type) {
        mapsServer.expect(request -> { }).andRespond(withSuccess(
                regions("123", "1").replace("LAND_NUMBER", type), MediaType.APPLICATION_JSON));

        assertThat(repository.search("역삼동", LocationSearchType.REGION, 0, 5).items()).isEmpty();
    }

    @Test
    void 빈_결과는_성공한_빈_마지막_페이지다() {
        searchServer.expect(request -> { }).andRespond(withSuccess(
                "{\"total\":0,\"start\":1,\"display\":0,\"items\":[]}", MediaType.APPLICATION_JSON));

        LocationSearchPage result = repository.search("없는장소", LocationSearchType.PLACE, 0, 5);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalCount()).isZero();
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void 요청_유형에_필요한_인증_정보가_하나라도_없으면_외부_요청_없이_실패한다() {
        for (int missing = 0; missing < 4; missing++) {
            String[] keys = {"search-id", "search-secret", "maps-id", "maps-key"};
            keys[missing] = " ";
            NaverLocationSearchRepository unconfigured = new NaverLocationSearchRepository(searchClient, mapsClient,
                    keys[0], keys[1], keys[2], keys[3]);
            LocationSearchType type = LocationSearchType.PLACE;
            if (missing >= 2) {
                type = LocationSearchType.REGION;
            }
            LocationSearchType requestType = type;
            assertThatThrownBy(() -> unconfigured.search("서울역", requestType, 0, 5))
                    .isInstanceOf(LocationSearchUnavailableException.class);
        }
        searchServer.verify();
        mapsServer.verify();
    }

    @Test
    void 검색_API_키만_설정한_경우에도_장소_검색을_사용할_수_있다() {
        searchServer.expect(request -> { }).andRespond(withSuccess(places("1270300000"), MediaType.APPLICATION_JSON));
        NaverLocationSearchRepository placesOnly = new NaverLocationSearchRepository(searchClient, mapsClient,
                "search-id", "search-secret", "", "");

        assertThat(placesOnly.search("서울역", LocationSearchType.PLACE, 0, 5).items()).hasSize(1);
    }

    @Test
    void 외부_인증_실패는_빈_성공으로_위장하거나_응답_원문을_노출하지_않는다() {
        searchServer.expect(request -> { }).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .body("sensitive upstream body"));

        assertThatThrownBy(() -> repository.search("서울역", LocationSearchType.PLACE, 0, 5))
                .isInstanceOf(LocationSearchUnavailableException.class).hasMessageNotContaining("sensitive");
    }

    @Test
    void HTTP_성공이어도_Maps_상태가_실패면_외부_오류로_처리한다() {
        mapsServer.expect(request -> { }).andRespond(withSuccess(
                regions("", "1").replace("\"OK\"", "\"SYSTEM_ERROR\""), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> repository.search("역삼동", LocationSearchType.REGION, 0, 5))
                .isInstanceOf(LocationSearchUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1810000000", "\"not-a-coordinate\"", "127.03"})
    void 잘못된_좌표는_지도에서_사용하지_않고_외부_오류로_처리한다(String longitude) {
        searchServer.expect(request -> { }).andRespond(withSuccess(places(longitude), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> repository.search("서울역", LocationSearchType.PLACE, 0, 5))
                .isInstanceOf(LocationSearchUnavailableException.class);
    }

    @Test
    void 잘못된_응답_구조를_빈_성공으로_처리하지_않는다() {
        searchServer.expect(request -> { }).andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> repository.search("서울역", LocationSearchType.PLACE, 0, 5))
                .isInstanceOf(LocationSearchUnavailableException.class);
    }

    private String places(String longitude) {
        return """
                {"total":1,"start":1,"display":1,"items":[{
                  "title":"<b>서울역</b> &amp; 기관","category":"교통&gt;지하철역",
                  "address":"서울 중구 봉래동","roadAddress":"서울 중구 세종대로 1",
                  "mapx":%s,"mapy":"375000000"
                }]}
                """.formatted(longitude);
    }

    private String regions(String firstLandNumber, String secondLandNumber) {
        return """
                {"status":"OK","meta":{"totalCount":2,"page":1,"count":2},"addresses":[%s,%s]}
                """.formatted(region(firstLandNumber), region(secondLandNumber));
    }

    private String region(String landNumber) {
        return """
                {"jibunAddress":"서울특별시 강남구 역삼동","roadAddress":"","addressElements":[
                  {"types":["SIDO"],"longName":"서울특별시"},
                  {"types":["SIGUGUN"],"longName":"강남구"},
                  {"types":["DONGMYUN"],"longName":"역삼동"},
                  {"types":["RI"],"longName":""},
                  {"types":["LAND_NUMBER"],"longName":"%s"}
                ],"x":"127.03","y":"37.5"}
                """.formatted(landNumber);
    }
}
