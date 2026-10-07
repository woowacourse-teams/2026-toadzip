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
    void 장소_검색_계약과_페이지를_반환한다() throws Exception {
        when(service.search(any())).thenReturn(new LocationSearchResponse(List.of(
                new LocationSearchItemResponse(LocationSearchType.PLACE, "123", "서울역", "서울 중구",
                        new BigDecimal("37.5"), new BigDecimal("127.03"))
        ), 1, 5, true, 12L));

        mockMvc.perform(get("/api/v1/locations/search").param("query", "서울역")
                        .param("type", "PLACE").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("PLACE"))
                .andExpect(jsonPath("$.data.items[0].latitude").value(37.5))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.totalCount").value(12));
        verify(service).search(argThat(request -> request.type() == LocationSearchType.PLACE && request.page() == 1));
    }

    @Test
    void 외부_검색_실패는_503과_고정된_오류_코드를_반환한다() throws Exception {
        when(service.search(any())).thenThrow(new LocationSearchUnavailableException());

        mockMvc.perform(get("/api/v1/locations/search").param("query", "서울역").param("type", "PLACE"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LOCATION_SEARCH_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 알_수_없는_위치_검색_유형은_400이다() throws Exception {
        mockMvc.perform(get("/api/v1/locations/search").param("query", "서울역").param("type", "COMPLEX"))
                .andExpect(status().isBadRequest());
    }
}
