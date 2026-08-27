package com.toadzip.backend.housing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.ComplexSort;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.dto.request.HousingComplexSearchRequest;
import com.toadzip.backend.housing.dto.response.AgencyResponse;
import com.toadzip.backend.housing.dto.response.CurrentAnnouncementResponse;
import com.toadzip.backend.housing.dto.response.CurrentSupplyConditionResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexAddressResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexDetailResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexMapItemResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexMapResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexListItemResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexListResponse;
import com.toadzip.backend.housing.dto.response.HousingTypeDetailResponse;
import com.toadzip.backend.housing.dto.response.RepresentativeAnnouncementResponse;
import com.toadzip.backend.housing.exception.HousingComplexNotFoundException;
import com.toadzip.backend.housing.exception.InvalidComplexCursorException;
import com.toadzip.backend.housing.exception.InvalidComplexRequestException;
import com.toadzip.backend.housing.exception.InvalidMapBoundsException;
import com.toadzip.backend.housing.service.HousingComplexQueryService;

@WebMvcTest(HousingComplexController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(HousingComplexExceptionAdvice.class)
class HousingComplexControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HousingComplexQueryService queryService;

    @Test
    void 목록의_검색_필터와_정렬_커서_크기를_Service에_전달한다() throws Exception {
        when(queryService.getComplexes(
                any(HousingComplexSearchRequest.class),
                eq(ComplexSort.DEPOSIT_ASC),
                eq("next-cursor"),
                eq(7)
        )).thenReturn(listResponse(17L));

        mockMvc.perform(get("/api/v1/complexes")
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
                        .param("sort", "DEPOSIT_ASC")
                        .param("cursor", "next-cursor")
                        .param("size", "7")
                        .param("southWestLat", "37.4")
                        .param("southWestLng", "126.8")
                        .param("northEastLat", "37.6")
                        .param("northEastLng", "127.1"))
                .andExpect(status().isOk());

        ArgumentCaptor<HousingComplexSearchRequest> requestCaptor =
                ArgumentCaptor.forClass(HousingComplexSearchRequest.class);
        verify(queryService).getComplexes(
                requestCaptor.capture(),
                eq(ComplexSort.DEPOSIT_ASC),
                eq("next-cursor"),
                eq(7)
        );
        assertBoundSearchRequest(requestCaptor.getValue());
    }

    @Test
    void 목록의_정렬_커서_크기_생략값을_Service에_전달한다() throws Exception {
        when(queryService.getComplexes(
                any(HousingComplexSearchRequest.class),
                eq(ComplexSort.LATEST_ANNOUNCEMENT),
                eq(null),
                eq(20)
        )).thenReturn(listResponse(20L));

        mockMvc.perform(get("/api/v1/complexes")
                        .param("southWestLat", "37.4")
                        .param("southWestLng", "126.8")
                        .param("northEastLat", "37.6")
                        .param("northEastLng", "127.1"))
                .andExpect(status().isOk());

        verify(queryService).getComplexes(
                any(HousingComplexSearchRequest.class),
                eq(ComplexSort.LATEST_ANNOUNCEMENT),
                eq(null),
                eq(20)
        );
    }

    @Test
    void 지도의_검색_필터와_경계를_Service에_전달한다() throws Exception {
        when(queryService.getComplexesForMap(any(HousingComplexSearchRequest.class)))
                .thenReturn(new HousingComplexMapResponse(List.of()));

        mockMvc.perform(get("/api/v1/complexes/map")
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
        verify(queryService).getComplexesForMap(requestCaptor.capture());
        assertBoundSearchRequest(requestCaptor.getValue());
    }

    @ParameterizedTest
    @MethodSource("malformedSearchParameters")
    void 변환할_수_없는_검색값은_VALIDATION_FAILED와_field를_반환한다(
            String field,
            String malformedValue
    ) throws Exception {
        mockMvc.perform(get("/api/v1/complexes")
                        .param(field, malformedValue)
                        .param("southWestLat", "37.4")
                        .param("southWestLng", "126.8")
                        .param("northEastLat", "37.6")
                        .param("northEastLng", "127.1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("요청값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(noInternalDetails());
    }

    @Test
    void 빈_enum_요소는_INVALID_REQUEST를_반환한다() throws Exception {
        doThrow(new InvalidComplexRequestException())
                .when(queryService)
                .getComplexes(
                        any(HousingComplexSearchRequest.class),
                        eq(ComplexSort.LATEST_ANNOUNCEMENT),
                        eq(null),
                        eq(20)
                );

        mockMvc.perform(get("/api/v1/complexes")
                        .param("agencyCodes", "LH", "")
                        .param("southWestLat", "37.4")
                        .param("southWestLng", "126.8")
                        .param("northEastLat", "37.6")
                        .param("northEastLng", "127.1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("단지 조회 요청값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(noInternalDetails());

        ArgumentCaptor<HousingComplexSearchRequest> requestCaptor =
                ArgumentCaptor.forClass(HousingComplexSearchRequest.class);
        verify(queryService).getComplexes(
                requestCaptor.capture(),
                eq(ComplexSort.LATEST_ANNOUNCEMENT),
                eq(null),
                eq(20)
        );
        assertThat(requestCaptor.getValue().agencyCodes()).containsExactly(AgencyCode.LH, null);
    }

    @Test
    void 네_좌표로_지도_영역_단지_요약을_조회한다() throws Exception {
        when(queryService.getComplexesForMap(any(HousingComplexSearchRequest.class)))
                .thenReturn(new HousingComplexMapResponse(List.of(
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
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("data")))
                .andExpect(jsonPath("$.data.keys()", containsInAnyOrder("items")))
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
        when(queryService.getComplexesForMap(any(HousingComplexSearchRequest.class)))
                .thenThrow(new InvalidMapBoundsException());

        mockMvc.perform(get("/api/v1/complexes/map")
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
        when(queryService.getComplexesForMap(any(HousingComplexSearchRequest.class)))
                .thenThrow(new InvalidMapBoundsException());

        mockMvc.perform(get("/api/v1/complexes/map"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("code", "message", "traceId")))
                .andExpect(jsonPath("$.code").value("INVALID_MAP_BOUNDS"))
                .andExpect(jsonPath("$.message").value("지도 범위 좌표가 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(noInternalDetails());
    }

    @Test
    void 뒤집힌_지도_경계는_INVALID_MAP_BOUNDS를_반환한다() throws Exception {
        when(queryService.getComplexesForMap(any(HousingComplexSearchRequest.class)))
                .thenThrow(new InvalidMapBoundsException());

        mockMvc.perform(get("/api/v1/complexes/map")
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
        when(queryService.getComplexesForMap(any(HousingComplexSearchRequest.class)))
                .thenThrow(new InvalidMapBoundsException());

        mockMvc.perform(get("/api/v1/complexes/map")
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
        when(queryService.getComplexesForMap(any(HousingComplexSearchRequest.class)))
                .thenThrow(new InvalidMapBoundsException());

        mockMvc.perform(get("/api/v1/complexes/map")
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
        mockMvc.perform(get("/api/v1/complexes/map")
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
    void 네_좌표와_크기로_커서_단지_목록_envelope을_조회한다() throws Exception {
        when(queryService.getComplexes(
                any(HousingComplexSearchRequest.class),
                eq(ComplexSort.LATEST_ANNOUNCEMENT),
                eq("cursor"),
                eq(2)
        )).thenReturn(listResponse(17L));

        mockMvc.perform(get("/api/v1/complexes")
                        .param("cursor", "cursor")
                        .param("size", "2")
                        .param("southWestLat", "37.400000")
                        .param("southWestLng", "126.800000")
                        .param("northEastLat", "37.600000")
                        .param("northEastLng", "127.100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("data")))
                .andExpect(jsonPath("$.data.keys()", containsInAnyOrder("items", "nextCursor", "hasNext")))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].keys()", containsInAnyOrder(
                        "complexId",
                        "thumbnailImageUrl",
                        "regionName",
                        "name",
                        "rentalType",
                        "agency",
                        "exclusiveAreaMin",
                        "exclusiveAreaMax",
                        "depositMin",
                        "depositMax",
                        "monthlyRentMin",
                        "monthlyRentMax",
                        "representativeAnnouncement"
                )))
                .andExpect(jsonPath("$.data.items[0].complexId").value(17))
                .andExpect(jsonPath("$.data.items[0].thumbnailImageUrl").value("https://example.com/image.png"))
                .andExpect(jsonPath("$.data.items[0].regionName").value("서울특별시 중구"))
                .andExpect(jsonPath("$.data.items[0].name").value("행복 단지"))
                .andExpect(jsonPath("$.data.items[0].rentalType").value("HAPPY_HOUSING"))
                .andExpect(jsonPath("$.data.items[0].agency.code").value("LH"))
                .andExpect(jsonPath("$.data.items[0].agency.name").value("한국토지주택공사"))
                .andExpect(jsonPath("$.data.items[0].exclusiveAreaMin").value(36.12))
                .andExpect(jsonPath("$.data.items[0].exclusiveAreaMax").value(44.87))
                .andExpect(jsonPath("$.data.items[0].depositMin").value(50000000))
                .andExpect(jsonPath("$.data.items[0].depositMax").value(70000000))
                .andExpect(jsonPath("$.data.items[0].monthlyRentMin").value(200000))
                .andExpect(jsonPath("$.data.items[0].monthlyRentMax").value(300000))
                .andExpect(jsonPath(
                        "$.data.items[0].representativeAnnouncement.keys()",
                        containsInAnyOrder(
                                "announcementId",
                                "publicationType",
                                "applicationStatus",
                                "applicationEndAt",
                                "dDay"
                        )
                ))
                .andExpect(jsonPath("$.data.items[0].representativeAnnouncement.announcementId").value(117))
                .andExpect(jsonPath("$.data.items[0].representativeAnnouncement.publicationType").value("ORIGINAL"))
                .andExpect(jsonPath("$.data.items[0].representativeAnnouncement.applicationStatus")
                        .value("APPLYING"))
                .andExpect(jsonPath("$.data.items[0].representativeAnnouncement.applicationEndAt")
                        .value("2026-08-27"))
                .andExpect(jsonPath("$.data.items[0].representativeAnnouncement.dDay").value(0))
                .andExpect(jsonPath("$.data.nextCursor").value("next-cursor"))
                .andExpect(jsonPath("$.data.hasNext").value(true));
    }

    @Test
    void size를_생략하면_기본값_20으로_단지_목록을_조회한다() throws Exception {
        when(queryService.getComplexes(
                any(HousingComplexSearchRequest.class),
                eq(ComplexSort.LATEST_ANNOUNCEMENT),
                eq(null),
                eq(20)
        )).thenReturn(listResponse(20L));

        mockMvc.perform(get("/api/v1/complexes")
                        .param("southWestLat", "37.400000")
                        .param("southWestLng", "126.800000")
                        .param("northEastLat", "37.600000")
                        .param("northEastLng", "127.100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].complexId").value(20));
    }

    @Test
    void 단지_목록_좌표_하나를_생략하면_INVALID_MAP_BOUNDS를_반환한다() throws Exception {
        when(queryService.getComplexes(
                any(HousingComplexSearchRequest.class),
                eq(ComplexSort.LATEST_ANNOUNCEMENT),
                eq(null),
                eq(20)
        )).thenThrow(new InvalidMapBoundsException());

        mockMvc.perform(get("/api/v1/complexes")
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
    void 잘못된_목록_커서는_INVALID_CURSOR를_반환한다() throws Exception {
        when(queryService.getComplexes(
                any(HousingComplexSearchRequest.class),
                eq(ComplexSort.LATEST_ANNOUNCEMENT),
                eq("bad"),
                anyInt()
        ))
                .thenThrow(new InvalidComplexCursorException());

        mockMvc.perform(get("/api/v1/complexes")
                        .param("cursor", "bad")
                        .param("southWestLat", "37.400000")
                        .param("southWestLng", "126.800000")
                        .param("northEastLat", "37.600000")
                        .param("northEastLng", "127.100000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("code", "message", "traceId")))
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.message").value("단지 조회 커서가 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(noInternalDetails());
    }

    @Test
    void size_51은_INVALID_REQUEST를_반환한다() throws Exception {
        when(queryService.getComplexes(
                any(HousingComplexSearchRequest.class),
                eq(ComplexSort.LATEST_ANNOUNCEMENT),
                eq(null),
                eq(51)
        ))
                .thenThrow(new InvalidComplexRequestException());

        mockMvc.perform(get("/api/v1/complexes")
                        .param("size", "51")
                        .param("southWestLat", "37.400000")
                        .param("southWestLng", "126.800000")
                        .param("northEastLat", "37.600000")
                        .param("northEastLng", "127.100000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("code", "message", "traceId")))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("단지 조회 요청값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(noInternalDetails());
    }

    @Test
    void size_0은_INVALID_REQUEST를_반환한다() throws Exception {
        when(queryService.getComplexes(
                any(HousingComplexSearchRequest.class),
                eq(ComplexSort.LATEST_ANNOUNCEMENT),
                eq(null),
                eq(0)
        ))
                .thenThrow(new InvalidComplexRequestException());

        mockMvc.perform(get("/api/v1/complexes")
                        .param("size", "0")
                        .param("southWestLat", "37.400000")
                        .param("southWestLng", "126.800000")
                        .param("northEastLat", "37.600000")
                        .param("northEastLng", "127.100000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("code", "message", "traceId")))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("단지 조회 요청값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(noInternalDetails());
    }

    @Test
    void 숫자가_아닌_size는_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/complexes")
                        .param("size", "not-a-number")
                        .param("southWestLat", "37.400000")
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
                .andExpect(jsonPath("$.errors[0].field").value("size"))
                .andExpect(noInternalDetails());
    }

    @Test
    void 숫자가_아닌_단지_ID는_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/complexes/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("code", "message", "traceId", "errors")))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("요청값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].keys()", containsInAnyOrder("field", "reason")))
                .andExpect(jsonPath("$.errors[0].field").value("complexId"))
                .andExpect(noInternalDetails());
    }

    @Test
    void 단지_상세를_정확한_ApiResponse_envelope과_JSON_key로_조회한다() throws Exception {
        when(queryService.getComplex(17L)).thenReturn(detailResponse());

        mockMvc.perform(get("/api/v1/complexes/17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("data")))
                .andExpect(jsonPath("$.data.keys()", containsInAnyOrder(
                        "complexId",
                        "name",
                        "rentalType",
                        "agency",
                        "address",
                        "completionDate",
                        "buildingType",
                        "hasElevator",
                        "heatingType",
                        "corridorType",
                        "moveOutCountLastYear",
                        "totalHouseholdCount",
                        "totalParkingCount",
                        "images",
                        "overviewImageUrl",
                        "housingTypes",
                        "currentAnnouncements"
                )))
                .andExpect(jsonPath("$.data.complexId").value(17))
                .andExpect(jsonPath("$.data.agency.keys()", containsInAnyOrder("code", "name")))
                .andExpect(jsonPath("$.data.address.keys()", containsInAnyOrder(
                        "regionName",
                        "roadAddress",
                        "latitude",
                        "longitude"
                )))
                .andExpect(jsonPath("$.data.address.regionName").value("서울특별시 중구"))
                .andExpect(jsonPath("$.data.address.latitude").value(37.500000))
                .andExpect(jsonPath("$.data.address.longitude").value(126.900000))
                .andExpect(jsonPath("$.data.images[0]").value("https://example.com/complex.png"))
                .andExpect(jsonPath("$.data.overviewImageUrl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.housingTypes[0].keys()", containsInAnyOrder(
                        "housingTypeId",
                        "name",
                        "exclusiveArea",
                        "supplyArea",
                        "floorPlanImageUrl",
                        "floorPlan3dImageUrl",
                        "isDuplex",
                        "maintenanceFee",
                        "currentSupplyConditions"
                )))
                .andExpect(jsonPath("$.data.housingTypes[0].currentSupplyConditions[0].keys()",
                        containsInAnyOrder("target", "deposit", "monthlyRent", "convertibleDeposit")))
                .andExpect(jsonPath("$.data.currentAnnouncements[0].keys()", containsInAnyOrder(
                        "announcementId",
                        "title",
                        "publicationType",
                        "applicationStatus",
                        "targets",
                        "applicationStartAt",
                        "applicationEndAt",
                        "dDay",
                        "actualCompetitionRate"
                )))
                .andExpect(jsonPath("$.data.currentAnnouncements[0].applicationStartAt")
                        .value("2026-08-20"))
                .andExpect(jsonPath("$.data.currentAnnouncements[0].applicationEndAt")
                        .value("2026-08-27"));
    }

    @Test
    void 없는_단지_상세는_404_COMPLEX_NOT_FOUND를_반환한다() throws Exception {
        when(queryService.getComplex(999L)).thenThrow(new HousingComplexNotFoundException());

        mockMvc.perform(get("/api/v1/complexes/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.keys()", containsInAnyOrder("code", "message", "traceId")))
                .andExpect(jsonPath("$.code").value("COMPLEX_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("단지를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(noInternalDetails());
    }

    private static Stream<Arguments> malformedSearchParameters() {
        return Stream.of(
                Arguments.of("agencyCodes", "UNKNOWN"),
                Arguments.of("minDeposit", "not-a-long"),
                Arguments.of("minExclusiveArea", "not-a-decimal"),
                Arguments.of("hasElevator", "not-a-boolean")
        );
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

    private HousingComplexListResponse listResponse(long complexId) {
        return new HousingComplexListResponse(
                List.of(new HousingComplexListItemResponse(
                        complexId,
                        "https://example.com/image.png",
                        "서울특별시 중구",
                        "행복 단지",
                        "HAPPY_HOUSING",
                        new AgencyResponse("LH", "한국토지주택공사"),
                        new BigDecimal("36.12"),
                        new BigDecimal("44.87"),
                        50000000L,
                        70000000L,
                        200000L,
                        300000L,
                        new RepresentativeAnnouncementResponse(
                                117L,
                                "ORIGINAL",
                                "APPLYING",
                                LocalDate.of(2026, 8, 27),
                                0
                        )
                )),
                "next-cursor",
                true
        );
    }

    private HousingComplexDetailResponse detailResponse() {
        return new HousingComplexDetailResponse(
                17L,
                "행복 단지",
                "HAPPY_HOUSING",
                new AgencyResponse("LH", "한국토지주택공사"),
                new HousingComplexAddressResponse(
                        "서울특별시 중구",
                        "서울특별시 중구 세종대로 110",
                        new BigDecimal("37.500000"),
                        new BigDecimal("126.900000")
                ),
                LocalDate.of(2020, 1, 1),
                "APARTMENT",
                true,
                "INDIVIDUAL",
                "STAIR",
                7,
                100,
                80,
                List.of("https://example.com/complex.png"),
                null,
                List.of(new HousingTypeDetailResponse(
                        101L,
                        "36A",
                        new BigDecimal("36.12"),
                        null,
                        "https://example.com/floor.png",
                        null,
                        false,
                        null,
                        List.of(new CurrentSupplyConditionResponse(
                                "청년",
                                50000000L,
                                200000L,
                                null
                        ))
                )),
                List.of(new CurrentAnnouncementResponse(
                        201L,
                        "행복주택 모집 공고",
                        "ORIGINAL",
                        "APPLYING",
                        List.of("청년"),
                        LocalDate.of(2026, 8, 20),
                        LocalDate.of(2026, 8, 27),
                        0,
                        null
                ))
        );
    }

    private ResultMatcher noInternalDetails() {
        return result -> {
            String body = result.getResponse().getContentAsString();
            assertFalse(body.contains("SQL"));
            assertFalse(body.contains("Exception"));
            assertFalse(body.contains("java."));
            assertFalse(body.contains("org.springframework"));
            assertFalse(body.contains("stackTrace"));
        };
    }
}
