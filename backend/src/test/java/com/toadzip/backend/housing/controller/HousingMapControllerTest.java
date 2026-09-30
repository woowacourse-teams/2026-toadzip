package com.toadzip.backend.housing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.dto.request.HousingComplexSearchRequest;
import com.toadzip.backend.housing.dto.response.AgencyResponse;
import com.toadzip.backend.housing.dto.response.HousingMapAggregateNodeResponse;
import com.toadzip.backend.housing.dto.response.HousingMapIndividualNodeResponse;
import com.toadzip.backend.housing.dto.response.HousingMapNodeResponse;
import com.toadzip.backend.housing.dto.response.HousingMapRepresentation;
import com.toadzip.backend.housing.dto.response.HousingMapResponse;
import com.toadzip.backend.housing.exception.InvalidComplexRequestException;
import com.toadzip.backend.housing.exception.InvalidMapBoundsException;
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
import org.springframework.test.web.servlet.ResultMatcher;

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
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("data")))
                .andExpect(jsonPath("$.data.keys()", containsInAnyOrder(
                        "resolvedStage", "representation", "policyVersion", "regionDatasetVersion", "nodes"
                )))
                .andExpect(jsonPath("$.data.nodes.length()").value(1))
                .andExpect(jsonPath("$.data.nodes[0].keys()", containsInAnyOrder(
                        "type",
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
                .andExpect(jsonPath("$.data.nodes[0].type").value("INDIVIDUAL"))
                .andExpect(jsonPath("$.data.nodes[0].complexId").value(17))
                .andExpect(jsonPath("$.data.nodes[0].name").value("행복 단지"))
                .andExpect(jsonPath("$.data.nodes[0].latitude").value(37.500000))
                .andExpect(jsonPath("$.data.nodes[0].longitude").value(126.900000))
                .andExpect(jsonPath("$.data.nodes[0].rentalType").value("HAPPY_HOUSING"))
                .andExpect(jsonPath("$.data.nodes[0].agency.code").value("LH"))
                .andExpect(jsonPath("$.data.nodes[0].agency.name").value("한국토지주택공사"))
                .andExpect(jsonPath("$.data.nodes[0].exclusiveAreaMin").value(36.12))
                .andExpect(jsonPath("$.data.nodes[0].exclusiveAreaMax").value(44.87))
                .andExpect(jsonPath("$.data.nodes[0].depositMin").value(50000000))
                .andExpect(jsonPath("$.data.nodes[0].depositMax").value(70000000))
                .andExpect(jsonPath("$.data.nodes[0].monthlyRentMin").value(200000))
                .andExpect(jsonPath("$.data.nodes[0].monthlyRentMax").value(300000))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.data.hasNext").doesNotExist());
    }

    @Test
    void 지도의_검색_필터와_경계를_Service에_전달한다() throws Exception {
        when(queryService.getMap(any(HousingComplexSearchRequest.class), eq(decimal("14.00")), eq(null)))
                .thenReturn(individualResponse());

        mockMvc.perform(get("/api/v2/complexes/map").param("zoom", "14.00")
                        .param("keyword", " 행복 단지 ")
                        .param("regionCode", "11140")
                        .param("rentalTypes", "HAPPY_HOUSING", "NATIONAL_RENTAL")
                        .param("applicationStatuses", "APPLYING", "CLOSED")
                        .param("agencyCodes", "LH", "SH")
                        .param("recruitmentTypes", "NEW", "WAITLIST")
                        .param("minDeposit", "10000000")
                        .param("maxDeposit", "70000000")
                        .param("minMonthlyRent", "100000")
                        .param("maxMonthlyRent", "300000")
                        .param("minExclusiveArea", "36.12")
                        .param("maxExclusiveArea", "44.87")
                        .param("builtYearFrom", "2018")
                        .param("builtYearTo", "2026")
                        .param("hasElevator", "true")
                        .param("southWestLat", "37.4")
                        .param("southWestLng", "126.8")
                        .param("northEastLat", "37.6")
                        .param("northEastLng", "127.1"))
                .andExpect(status().isOk());

        ArgumentCaptor<HousingComplexSearchRequest> requestCaptor =
                ArgumentCaptor.forClass(HousingComplexSearchRequest.class);
        verify(queryService).getMap(requestCaptor.capture(), eq(decimal("14.00")), eq(null));
        assertBoundSearchRequest(requestCaptor.getValue());
    }

    @Test
    void 좌표_하나를_생략하면_INVALID_MAP_BOUNDS를_반환한다() throws Exception {
        when(queryService.getMap(any(HousingComplexSearchRequest.class), eq(decimal("14.00")), eq(null)))
                .thenThrow(new InvalidMapBoundsException());

        mockMvc.perform(get("/api/v2/complexes/map").param("zoom", "14.00")
                        .param("southWestLat", "37.400000")
                        .param("southWestLng", "126.800000")
                        .param("northEastLat", "37.600000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("code", "message", "traceId")))
                .andExpect(jsonPath("$.code").value("INVALID_MAP_BOUNDS"))
                .andExpect(jsonPath("$.message").value("지도 범위 좌표가 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(noInternalDetails());
    }

    @Test
    void 네_지도_경계를_모두_생략하면_INVALID_MAP_BOUNDS를_반환한다() throws Exception {
        when(queryService.getMap(any(HousingComplexSearchRequest.class), eq(decimal("14.00")), eq(null)))
                .thenThrow(new InvalidMapBoundsException());

        mockMvc.perform(get("/api/v2/complexes/map").param("zoom", "14.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("code", "message", "traceId")))
                .andExpect(jsonPath("$.code").value("INVALID_MAP_BOUNDS"))
                .andExpect(jsonPath("$.message").value("지도 범위 좌표가 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(noInternalDetails());
    }

    @Test
    void 뒤집힌_지도_경계는_INVALID_MAP_BOUNDS를_반환한다() throws Exception {
        when(queryService.getMap(any(HousingComplexSearchRequest.class), eq(decimal("14.00")), eq(null)))
                .thenThrow(new InvalidMapBoundsException());

        mockMvc.perform(get("/api/v2/complexes/map").param("zoom", "14.00")
                        .param("southWestLat", "37.600000")
                        .param("southWestLng", "126.800000")
                        .param("northEastLat", "37.400000")
                        .param("northEastLng", "127.100000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("code", "message", "traceId")))
                .andExpect(jsonPath("$.code").value("INVALID_MAP_BOUNDS"))
                .andExpect(jsonPath("$.message").value("지도 범위 좌표가 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(noInternalDetails());
    }

    @Test
    void 동일한_지도_경계는_INVALID_MAP_BOUNDS를_반환한다() throws Exception {
        when(queryService.getMap(any(HousingComplexSearchRequest.class), eq(decimal("14.00")), eq(null)))
                .thenThrow(new InvalidMapBoundsException());

        mockMvc.perform(get("/api/v2/complexes/map").param("zoom", "14.00")
                        .param("southWestLat", "37.400000")
                        .param("southWestLng", "126.800000")
                        .param("northEastLat", "37.400000")
                        .param("northEastLng", "127.100000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("code", "message", "traceId")))
                .andExpect(jsonPath("$.code").value("INVALID_MAP_BOUNDS"))
                .andExpect(jsonPath("$.message").value("지도 범위 좌표가 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(noInternalDetails());
    }

    @Test
    void 허용_범위를_벗어난_지도_경계는_INVALID_MAP_BOUNDS를_반환한다() throws Exception {
        when(queryService.getMap(any(HousingComplexSearchRequest.class), eq(decimal("14.00")), eq(null)))
                .thenThrow(new InvalidMapBoundsException());

        mockMvc.perform(get("/api/v2/complexes/map").param("zoom", "14.00")
                        .param("southWestLat", "-91")
                        .param("southWestLng", "126.800000")
                        .param("northEastLat", "37.600000")
                        .param("northEastLng", "127.100000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("code", "message", "traceId")))
                .andExpect(jsonPath("$.code").value("INVALID_MAP_BOUNDS"))
                .andExpect(jsonPath("$.message").value("지도 범위 좌표가 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(noInternalDetails());
    }

    @Test
    void 숫자가_아닌_bounds는_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v2/complexes/map").param("zoom", "14.00")
                        .param("southWestLat", "not-a-number")
                        .param("southWestLng", "126.800000")
                        .param("northEastLat", "37.600000")
                        .param("northEastLng", "127.100000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("code", "message", "traceId", "errors")))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("요청값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].keys()", containsInAnyOrder("field", "reason")))
                .andExpect(jsonPath("$.errors[0].field").value("southWestLat"));
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

    private void assertBoundSearchRequest(HousingComplexSearchRequest request) {
        assertThat(request.keyword()).isEqualTo(" 행복 단지 ");
        assertThat(request.regionCode()).isEqualTo("11140");
        assertThat(request.rentalTypes())
                .containsExactly(RentalType.HAPPY_HOUSING, RentalType.NATIONAL_RENTAL);
        assertThat(request.applicationStatuses())
                .containsExactly(ApplicationStatus.APPLYING, ApplicationStatus.CLOSED);
        assertThat(request.agencyCodes()).containsExactly(AgencyCode.LH, AgencyCode.SH);
        assertThat(request.recruitmentTypes())
                .containsExactly(RecruitmentType.NEW, RecruitmentType.WAITLIST);
        assertThat(request.minDeposit()).isEqualTo(10_000_000L);
        assertThat(request.maxDeposit()).isEqualTo(70_000_000L);
        assertThat(request.minMonthlyRent()).isEqualTo(100_000L);
        assertThat(request.maxMonthlyRent()).isEqualTo(300_000L);
        assertThat(request.minExclusiveArea()).isEqualByComparingTo("36.12");
        assertThat(request.maxExclusiveArea()).isEqualByComparingTo("44.87");
        assertThat(request.builtYearFrom()).isEqualTo(2018);
        assertThat(request.builtYearTo()).isEqualTo(2026);
        assertThat(request.hasElevator()).isTrue();
        assertThat(request.southWestLat()).isEqualByComparingTo("37.4");
        assertThat(request.southWestLng()).isEqualByComparingTo("126.8");
        assertThat(request.northEastLat()).isEqualByComparingTo("37.6");
        assertThat(request.northEastLng()).isEqualByComparingTo("127.1");
    }

    private ResultMatcher noInternalDetails() {
        return result -> {
            String body = result.getResponse().getContentAsString();
            assertFalse(body.contains("SQL"));
            assertFalse(body.contains("Exception"));
            assertFalse(body.contains("java."));
            assertFalse(body.contains("org.springframework"));
            assertFalse(body.contains("com.toadzip"));
            assertFalse(body.contains("Failed to convert"));
            assertFalse(body.contains("For input string"));
            assertFalse(body.contains("stackTrace"));
            assertFalse(body.contains("\"stack\""));
            assertFalse(body.contains("\"stackTrace\""));
            assertFalse(body.contains("\"cause\""));
            assertFalse(body.contains("\"exception\""));
            assertFalse(body.contains("\"exceptionType\""));
            assertFalse(body.contains("\"type\""));
            assertFalse(body.contains("\"class\""));
        };
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
                "INDIVIDUAL", 17L, "행복 단지", decimal("37.500000"), decimal("126.900000"),
                "HAPPY_HOUSING", new AgencyResponse("LH", "한국토지주택공사"),
                decimal("36.12"), decimal("44.87"), 50000000L, 70000000L, 200000L, 300000L
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
