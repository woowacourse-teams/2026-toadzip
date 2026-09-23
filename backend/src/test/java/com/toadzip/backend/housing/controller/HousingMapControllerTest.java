package com.toadzip.backend.housing.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.housing.dto.request.HousingComplexSearchRequest;
import com.toadzip.backend.housing.dto.response.AgencyResponse;
import com.toadzip.backend.housing.dto.response.HousingMapAggregateNodeResponse;
import com.toadzip.backend.housing.dto.response.HousingMapIndividualNodeResponse;
import com.toadzip.backend.housing.dto.response.HousingMapNodeResponse;
import com.toadzip.backend.housing.dto.response.HousingMapRepresentation;
import com.toadzip.backend.housing.dto.response.HousingMapResponse;
import com.toadzip.backend.housing.exception.InvalidComplexRequestException;
import com.toadzip.backend.housing.service.HousingMapQueryService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HousingMapController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(HousingComplexExceptionAdvice.class)
class HousingMapControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HousingMapQueryService queryService;

    @Test
    void v2_지도_요청과_서버가_결정한_집계_node를_전달한다() throws Exception {
        when(queryService.getMap(any(), eq(decimal("10.10")), eq(2))).thenReturn(response());

        mockMvc.perform(get("/api/v2/complexes/map")
                        .param("regionCode", "41")
                        .param("rentalTypes", "HAPPY_HOUSING")
                        .param("southWestLat", "37.0")
                        .param("southWestLng", "126.0")
                        .param("northEastLat", "38.0")
                        .param("northEastLng", "128.0")
                        .param("zoom", "10.10")
                        .param("previousResolvedStage", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolvedStage").value(2))
                .andExpect(jsonPath("$.data.representation").value("AGGREGATE"))
                .andExpect(jsonPath("$.data.policyVersion").value("2026-09-02-v1"))
                .andExpect(jsonPath("$.data.regionDatasetVersion").value("2026-07-01"))
                .andExpect(jsonPath("$.data.nodes[0].type").value("AGGREGATE"))
                .andExpect(jsonPath("$.data.nodes[0].groupKey").value("METROPOLITAN:41"))
                .andExpect(jsonPath("$.data.nodes[0].groupLabel").value("경기"))
                .andExpect(jsonPath("$.data.nodes[0].uniqueComplexCount").value(42))
                .andExpect(jsonPath("$.data.nodes[0].nextStage").value(3))
                .andExpect(jsonPath("$.data.nodes[0].expansionZoom").value(11.0));

        ArgumentCaptor<HousingComplexSearchRequest> requestCaptor =
                ArgumentCaptor.forClass(HousingComplexSearchRequest.class);
        verify(queryService).getMap(requestCaptor.capture(), eq(decimal("10.10")), eq(2));
        assertEquals("41", requestCaptor.getValue().regionCode());
        assertEquals(decimal("37.0"), requestCaptor.getValue().southWestLat());
    }

    @Test
    void 개별_node의_기존_지도_필드를_전달한다() throws Exception {
        when(queryService.getMap(any(), eq(decimal("14.00")), eq(3)))
                .thenReturn(individualResponse());

        mockMvc.perform(validRequest("14.00", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolvedStage").value(4))
                .andExpect(jsonPath("$.data.representation").value("INDIVIDUAL"))
                .andExpect(jsonPath("$.data.nodes[0].type").value("INDIVIDUAL"))
                .andExpect(jsonPath("$.data.nodes[0].complexId").value(7))
                .andExpect(jsonPath("$.data.nodes[0].rentalType").value("HAPPY_HOUSING"))
                .andExpect(jsonPath("$.data.nodes[0].agency.code").value("LH"))
                .andExpect(jsonPath("$.data.nodes[0].depositMin").value(1000));
    }

    @Test
    void 음수_zoom은_INVALID_REQUEST를_반환한다() throws Exception {
        when(queryService.getMap(any(), eq(decimal("-0.01")), eq(2)))
                .thenThrow(new InvalidComplexRequestException());

        mockMvc.perform(validRequest("-0.01", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void zoom이_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v2/complexes/map")
                        .param("southWestLat", "37.0")
                        .param("southWestLng", "126.0")
                        .param("northEastLat", "38.0")
                        .param("northEastLng", "128.0"))
                .andExpect(status().isBadRequest());
    }

    private static HousingMapResponse response() {
        List<HousingMapNodeResponse> nodes = List.of(new HousingMapAggregateNodeResponse(
                "METROPOLITAN:41", "경기", decimal("37.4"), decimal("127.1"),
                42L, 3, decimal("11.00")
        ));
        return new HousingMapResponse(
                2, HousingMapRepresentation.AGGREGATE,
                "2026-09-02-v1", "2026-07-01", nodes
        );
    }

    private static HousingMapResponse individualResponse() {
        HousingMapIndividualNodeResponse node = new HousingMapIndividualNodeResponse(
                "INDIVIDUAL", 7L, "두꺼비 단지", decimal("37.4"), decimal("127.1"),
                "HAPPY_HOUSING", new AgencyResponse("LH", "한국토지주택공사"),
                decimal("36.0"), decimal("44.0"), 1000L, 2000L, 10L, 20L
        );
        return new HousingMapResponse(
                4, HousingMapRepresentation.INDIVIDUAL,
                "2026-09-02-v1", "2026-07-01", List.of(node)
        );
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validRequest(
            String zoom,
            String previousResolvedStage
    ) {
        return get("/api/v2/complexes/map")
                .param("southWestLat", "37.0")
                .param("southWestLng", "126.0")
                .param("northEastLat", "38.0")
                .param("northEastLng", "128.0")
                .param("zoom", zoom)
                .param("previousResolvedStage", previousResolvedStage);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
