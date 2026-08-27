package com.toadzip.backend.region.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.region.dto.response.RegionSearchItemResponse;
import com.toadzip.backend.region.dto.response.RegionSearchResponse;
import com.toadzip.backend.region.service.RegionQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(RegionController.class)
@AutoConfigureMockMvc(addFilters = false)
class RegionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegionQueryService regionQueryService;

    @Test
    void 지역_검색은_data_봉투에_정확한_필드와_순서로_응답한다() throws Exception {
        when(regionQueryService.searchRegions("서울")).thenReturn(searchResponse());

        mockMvc.perform(get("/api/v1/regions").param("keyword", "서울"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "data": {
                            "items": [
                              {
                                "regionCode": "11",
                                "provinceName": "서울특별시",
                                "districtName": null,
                                "displayName": "서울특별시 전체"
                              },
                              {
                                "regionCode": "11110",
                                "provinceName": "서울특별시",
                                "districtName": "종로구",
                                "displayName": "서울특별시 종로구"
                              }
                            ]
                          }
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void 검색_결과가_없으면_빈_items_배열을_반환한다() throws Exception {
        when(regionQueryService.searchRegions("제주")).thenReturn(new RegionSearchResponse(List.of()));

        mockMvc.perform(get("/api/v1/regions").param("keyword", "제주"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "data": {
                            "items": []
                          }
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void 누락_공백_초과길이_검색어는_keyword_필드_VALIDATION_FAILED를_반환한다() throws Exception {
        assertKeywordValidationError(mockMvc.perform(get("/api/v1/regions")));
        assertKeywordValidationError(mockMvc.perform(get("/api/v1/regions").param("keyword", "   ")));
        assertKeywordValidationError(mockMvc.perform(get("/api/v1/regions").param("keyword", "a".repeat(51))));
    }

    private RegionSearchResponse searchResponse() {
        return new RegionSearchResponse(List.of(
                new RegionSearchItemResponse("11", "서울특별시", null, "서울특별시 전체"),
                new RegionSearchItemResponse("11110", "서울특별시", "종로구", "서울특별시 종로구")
        ));
    }

    private void assertKeywordValidationError(ResultActions resultActions) throws Exception {
        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("keyword"));
    }
}
