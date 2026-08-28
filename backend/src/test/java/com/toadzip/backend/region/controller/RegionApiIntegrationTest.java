package com.toadzip.backend.region.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegionApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 인증없이_서울을_검색하면_집계와_자치구를_정규_순서로_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/regions").param("keyword", "서울"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.items.length()").value(26))
                .andExpect(jsonPath("$.data.items[*].regionCode", contains(
                        "11", "11110", "11140", "11170", "11200", "11215", "11230", "11260",
                        "11290", "11305", "11320", "11350", "11380", "11410", "11440", "11470",
                        "11500", "11530", "11545", "11560", "11590", "11620", "11650", "11680",
                        "11710", "11740"
                )))
                .andExpect(jsonPath("$.data.items[0].provinceName").value("서울특별시"))
                .andExpect(jsonPath("$.data.items[0].districtName").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].displayName").value("서울특별시 전체"));
    }

    @Test
    void 중구를_검색하면_광역시를_구분한_다섯_자치구만_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/regions").param("keyword", "중구"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(5))
                .andExpect(jsonPath("$.data.items[*].regionCode", contains(
                        "11140", "26110", "27110", "30140", "31110"
                )))
                .andExpect(jsonPath("$.data.items[*].districtName", contains(
                        "중구", "중구", "중구", "중구", "중구"
                )))
                .andExpect(jsonPath("$.data.items[*].displayName", contains(
                        "서울특별시 중구", "부산광역시 중구", "대구광역시 중구",
                        "대전광역시 중구", "울산광역시 중구"
                )));
    }

    @Test
    void 세종을_검색하면_집계와_실제_지역을_서로_다른_표시명으로_반환한다()
            throws Exception {
        mockMvc.perform(get("/api/v1/regions").param("keyword", "세종"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[*].regionCode", contains("36", "36110")))
                .andExpect(jsonPath("$.data.items[0].districtName").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].displayName").value("세종특별자치시 전체"))
                .andExpect(jsonPath("$.data.items[1].districtName").value("세종특별자치시"))
                .andExpect(jsonPath("$.data.items[1].displayName").value("세종특별자치시"));
    }

    @Test
    void 통합_지역_검색은_정규_12_접두사만_노출한다() throws Exception {
        mockMvc.perform(get("/api/v1/regions").param("keyword", "전남광주통합"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].regionCode").value("12"))
                .andExpect(jsonPath("$.data.items[*].regionCode", everyItem(startsWith("12"))));
    }
}
