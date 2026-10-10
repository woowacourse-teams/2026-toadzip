package com.toadzip.backend.search.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.search.domain.LocationSearchType;
import com.toadzip.backend.search.dto.response.LocationSearchItemResponse;
import com.toadzip.backend.search.dto.response.LocationSearchResponse;
import com.toadzip.backend.search.exception.LocationSearchUnavailableException;
import com.toadzip.backend.search.service.LocationSearchService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LocationSearchController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(SearchExceptionAdvice.class)
class LocationSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocationSearchService service;

    @Test
    void 지하철역_검색_계약과_페이지를_반환한다() throws Exception {
        when(service.search(any())).thenReturn(new LocationSearchResponse(List.of(
                new LocationSearchItemResponse(LocationSearchType.SUBWAY_STATION, "123", "서울역", "서울 중구",
                        new BigDecimal("37.5"), new BigDecimal("127.03"))
        ), 0, 5, false, 1L));

        mockMvc.perform(get("/api/v1/locations/search").param("query", "서울역")
                        .param("type", "SUBWAY_STATION").param("page", "0").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("SUBWAY_STATION"))
                .andExpect(jsonPath("$.data.items[0].latitude").value(37.5))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.totalCount").value(1));
        verify(service).search(argThat(request -> request.type() == LocationSearchType.SUBWAY_STATION && request.page() == 0));
    }

    @Test
    void 외부_검색_실패는_503과_고정된_오류_코드를_반환한다() throws Exception {
        when(service.search(any())).thenThrow(new LocationSearchUnavailableException());

        mockMvc.perform(get("/api/v1/locations/search").param("query", "서울역").param("type", "SUBWAY_STATION"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LOCATION_SEARCH_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLEX", "PLACE"})
    void 지원하지_않는_일반_장소와_위치_검색_유형은_400이다(String type) throws Exception {
        mockMvc.perform(get("/api/v1/locations/search").param("query", "서울역").param("type", type))
                .andExpect(status().isBadRequest());
    }
}
