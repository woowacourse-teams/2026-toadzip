package com.toadzip.backend.housing.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.housing.dto.response.AgencyResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexMapItemResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexMapResponse;
import com.toadzip.backend.housing.service.HousingComplexQueryService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HousingComplexController.class)
@Import(HousingComplexExceptionAdvice.class)
class HousingComplexControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HousingComplexQueryService queryService;

    @Test
    void 네_좌표로_지도_영역_단지_요약을_조회한다() throws Exception {
        when(queryService.getComplexesForMap(any())).thenReturn(new HousingComplexMapResponse(List.of(
                new HousingComplexMapItemResponse(
                        17L,
                        "행복 단지",
                        new BigDecimal("37.500000"),
                        new BigDecimal("126.900000"),
                        "HAPPY_HOUSING",
                        new AgencyResponse("LH", "한국토지주택공사"),
                        new BigDecimal("36.12"),
                        new BigDecimal("44.87"),
                        50000000L,
                        70000000L,
                        200000L,
                        300000L
                )
        )));

        mockMvc.perform(get("/api/v1/complexes/map")
                        .param("southWestLat", "37.400000")
                        .param("southWestLng", "126.800000")
                        .param("northEastLat", "37.600000")
                        .param("northEastLng", "127.100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].keys()", containsInAnyOrder(
                        "complexId",
                        "name",
                        "latitude",
                        "longitude",
                        "rentalType",
                        "agency",
                        "exclusiveAreaMin",
                        "exclusiveAreaMax",
                        "depositMin",
                        "depositMax",
                        "monthlyRentMin",
                        "monthlyRentMax"
                )))
                .andExpect(jsonPath("$.data.items[0].complexId").value(17))
                .andExpect(jsonPath("$.data.items[0].name").value("행복 단지"))
                .andExpect(jsonPath("$.data.items[0].latitude").value(37.500000))
                .andExpect(jsonPath("$.data.items[0].longitude").value(126.900000))
                .andExpect(jsonPath("$.data.items[0].rentalType").value("HAPPY_HOUSING"))
                .andExpect(jsonPath("$.data.items[0].agency.code").value("LH"))
                .andExpect(jsonPath("$.data.items[0].agency.name").value("한국토지주택공사"))
                .andExpect(jsonPath("$.data.items[0].exclusiveAreaMin").value(36.12))
                .andExpect(jsonPath("$.data.items[0].exclusiveAreaMax").value(44.87))
                .andExpect(jsonPath("$.data.items[0].depositMin").value(50000000))
                .andExpect(jsonPath("$.data.items[0].depositMax").value(70000000))
                .andExpect(jsonPath("$.data.items[0].monthlyRentMin").value(200000))
                .andExpect(jsonPath("$.data.items[0].monthlyRentMax").value(300000))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.data.hasNext").doesNotExist());
    }

    @Test
    void 좌표_하나를_생략하면_INVALID_MAP_BOUNDS를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/complexes/map")
                        .param("southWestLat", "37.400000")
                        .param("southWestLng", "126.800000")
                        .param("northEastLat", "37.600000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MAP_BOUNDS"))
                .andExpect(jsonPath("$.message").value("지도 범위 좌표가 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
