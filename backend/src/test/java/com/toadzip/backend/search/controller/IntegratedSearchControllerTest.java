package com.toadzip.backend.search.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.search.domain.SearchType;
import com.toadzip.backend.search.dto.response.IntegratedSearchResponse;
import com.toadzip.backend.search.dto.response.SearchResultItemResponse;
import com.toadzip.backend.search.exception.InvalidSearchRequestException;
import com.toadzip.backend.search.service.IntegratedSearchService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IntegratedSearchController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(SearchExceptionAdvice.class)
class IntegratedSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IntegratedSearchService service;

    @Test
    void 입력중_통합_검색_계약을_반환한다() throws Exception {
        when(service.search(any())).thenReturn(new IntegratedSearchResponse(
                "서울",
                List.of(),
                List.of(),
                List.of(new SearchResultItemResponse(
                        SearchType.REGION,
                        "11",
                        "서울특별시 전체",
                        "서울특별시",
                        null,
                        null,
                        null,
                        null,
                        "11"
                )),
                List.of(),
                0,
                8,
                false,
                null
        ));

        mockMvc.perform(get("/api/v1/search")
                        .param("query", "서울")
                        .param("preview", "true")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.query").value("서울"))
                .andExpect(jsonPath("$.data.announcements.length()").value(0))
                .andExpect(jsonPath("$.data.complexes.length()").value(0))
                .andExpect(jsonPath("$.data.regions[0].type").value("REGION"))
                .andExpect(jsonPath("$.data.regions[0].title").value("서울특별시 전체"))
                .andExpect(jsonPath("$.data.regions[0].regionCode").value("11"))
                .andExpect(jsonPath("$.data.failures.length()").value(0))
                .andExpect(jsonPath("$.data.size").value(8));
    }

    @Test
    void 잘못된_검색어는_고정된_오류_계약을_반환한다() throws Exception {
        when(service.search(any())).thenThrow(new InvalidSearchRequestException(
                "검색어는 공백 제외 2자 이상 50자 이하여야 한다."
        ));

        mockMvc.perform(get("/api/v1/search").param("query", "서"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEARCH_REQUEST"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 유형별_검색_쿼리를_전달하고_기존_응답_구조로_지역_좌표를_반환한다() throws Exception {
        when(service.search(any())).thenReturn(new IntegratedSearchResponse(
                "수원", List.of(), List.of(), List.of(new SearchResultItemResponse(
                        SearchType.REGION, "41110", "경기도 수원시", "경기도",
                        new BigDecimal("37.27532584"), new BigDecimal("127.01641895"), null, null, "41110"
                )), List.of(), 1, 5, true, 12L
        ));

        mockMvc.perform(get("/api/v1/search")
                        .param("query", "수원")
                        .param("type", "REGION")
                        .param("preview", "false")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.announcements.length()").value(0))
                .andExpect(jsonPath("$.data.complexes.length()").value(0))
                .andExpect(jsonPath("$.data.regions[0].latitude").value(37.27532584))
                .andExpect(jsonPath("$.data.regions[0].longitude").value(127.01641895))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.totalCount").value(12));
        verify(service).search(argThat(request -> request.type() == SearchType.REGION
                && request.page() == 1 && request.size() == 5 && !request.preview()));
    }

    @Test
    void 알_수_없는_검색_유형은_거부한다() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("query", "서울").param("type", "UNKNOWN"))
                .andExpect(status().isBadRequest());
    }
}
