package com.toadzip.backend.search.controller;

import static org.mockito.ArgumentMatchers.any;
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
                List.of(new SearchResultItemResponse(
                        SearchType.REGION,
                        "11",
                        "서울특별시 전체",
                        "서울특별시",
                        "서울특별시 전체",
                        "행정구역",
                        null,
                        null,
                        null,
                        null,
                        false,
                        "11",
                        0
                )),
                List.of(),
                0,
                8,
                false
        ));

        mockMvc.perform(get("/api/v1/search")
                        .param("query", "서울")
                        .param("preview", "true")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.query").value("서울"))
                .andExpect(jsonPath("$.data.housingInformation.length()").value(0))
                .andExpect(jsonPath("$.data.locations[0].type").value("REGION"))
                .andExpect(jsonPath("$.data.locations[0].title").value("서울특별시 전체"))
                .andExpect(jsonPath("$.data.locations[0].regionCode").value("11"))
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
}
