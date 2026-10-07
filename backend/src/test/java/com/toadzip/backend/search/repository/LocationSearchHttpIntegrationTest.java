package com.toadzip.backend.search.repository;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.search.controller.LocationSearchController;
import com.toadzip.backend.search.controller.SearchExceptionAdvice;
import com.toadzip.backend.search.service.LocationSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class LocationSearchHttpIntegrationTest {

    private MockRestServiceServer server;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openapi.naver.com");
        server = MockRestServiceServer.bindTo(builder).build();
        NaverLocationSearchRepository repository = new NaverLocationSearchRepository(
                builder.build(), RestClient.create(), "test-id", "test-secret", "", ""
        );
        mockMvc = MockMvcBuilders.standaloneSetup(new LocationSearchController(new LocationSearchService(repository)))
                .setControllerAdvice(new SearchExceptionAdvice()).build();
    }

    @Test
    void 네이버_응답을_서비스와_Controller를_거쳐_브라우저용_좌표와_텍스트로_반환한다() throws Exception {
        server.expect(header("X-Naver-Client-Id", "test-id"))
                .andExpect(header("X-Naver-Client-Secret", "test-secret"))
                .andRespond(withSuccess("""
                        {"total":1,"items":[{
                          "title":"<b>서울</b>시청","category":"공공&gt;시청",
                          "address":"서울특별시 중구 태평로1가 31","roadAddress":"서울특별시 중구 세종대로 110",
                          "mapx":"1269873882","mapy":"375666103"
                        }]}
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/locations/search").param("query", "서울시청").param("type", "PLACE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].title").value("서울시청"))
                .andExpect(jsonPath("$.data.items[0].subtitle").value("공공>시청 · 서울특별시 중구 세종대로 110"))
                .andExpect(jsonPath("$.data.items[0].latitude").value(37.5666103))
                .andExpect(jsonPath("$.data.items[0].longitude").value(126.9873882))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        server.verify();
    }

    @Test
    void 장소_다음_페이지는_외부_요청_없이_빈_마지막_페이지다() throws Exception {
        mockMvc.perform(get("/api/v1/locations/search").param("query", "서울시청")
                        .param("type", "PLACE").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false));
        server.verify();
    }

    @Test
    void 지역_키가_없으면_빈_성공_대신_503을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/locations/search").param("query", "역삼동").param("type", "REGION"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LOCATION_SEARCH_UNAVAILABLE"));
        server.verify();
    }
}
