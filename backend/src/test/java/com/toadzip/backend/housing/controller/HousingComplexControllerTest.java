package com.toadzip.backend.housing.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.exception.HousingComplexNotFoundException;
import com.toadzip.backend.housing.exception.InvalidComplexCursorException;
import com.toadzip.backend.housing.exception.InvalidComplexRequestException;
import com.toadzip.backend.housing.service.HousingComplexQueryService;
import java.math.BigDecimal;
import java.time.LocalDate;
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

    private static final MapBounds BOUNDS = MapBounds.of(
            new BigDecimal("37.400000"),
            new BigDecimal("126.800000"),
            new BigDecimal("37.600000"),
            new BigDecimal("127.100000")
    );

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

    @Test
    void 네_좌표와_크기로_커서_단지_목록_envelope을_조회한다() throws Exception {
        when(queryService.getComplexes(any(), eq("cursor"), eq(2))).thenReturn(listResponse(17L));

        mockMvc.perform(get("/api/v1/complexes")
                        .param("cursor", "cursor")
                        .param("size", "2")
                        .param("southWestLat", "37.400000")
                        .param("southWestLng", "126.800000")
                        .param("northEastLat", "37.600000")
                        .param("northEastLng", "127.100000"))
                .andExpect(status().isOk())
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
        when(queryService.getComplexes(BOUNDS, null, 20)).thenReturn(listResponse(20L));

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
        mockMvc.perform(get("/api/v1/complexes")
                        .param("southWestLat", "37.400000")
                        .param("southWestLng", "126.800000")
                        .param("northEastLat", "37.600000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MAP_BOUNDS"))
                .andExpect(jsonPath("$.message").value("지도 범위 좌표가 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 잘못된_목록_커서는_INVALID_CURSOR를_반환한다() throws Exception {
        when(queryService.getComplexes(any(), eq("bad"), anyInt()))
                .thenThrow(new InvalidComplexCursorException());

        mockMvc.perform(get("/api/v1/complexes")
                        .param("cursor", "bad")
                        .param("southWestLat", "37.400000")
                        .param("southWestLng", "126.800000")
                        .param("northEastLat", "37.600000")
                        .param("northEastLng", "127.100000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.message").value("단지 조회 커서가 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 잘못된_목록_크기는_INVALID_REQUEST를_반환한다() throws Exception {
        when(queryService.getComplexes(any(), any(), eq(51)))
                .thenThrow(new InvalidComplexRequestException());

        mockMvc.perform(get("/api/v1/complexes")
                        .param("size", "51")
                        .param("southWestLat", "37.400000")
                        .param("southWestLng", "126.800000")
                        .param("northEastLat", "37.600000")
                        .param("northEastLng", "127.100000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("단지 조회 요청값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
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
                .andExpect(jsonPath("$.code").value("COMPLEX_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("단지를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
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
}
