package com.toadzip.backend.housing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.dto.request.HousingComplexSearchRequest;
import com.toadzip.backend.housing.dto.response.HousingComplexMapResponse;
import com.toadzip.backend.housing.dto.response.HousingMapAggregateNodeResponse;
import com.toadzip.backend.housing.dto.response.HousingMapIndividualNodeResponse;
import com.toadzip.backend.housing.dto.response.HousingMapResponse;
import com.toadzip.backend.housing.dto.response.HousingMapRepresentation;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Transactional
class HousingMapQueryIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private HousingComplexQueryService legacyService;

    @Autowired
    private HousingMapQueryService clusteringService;

    @Test
    void 전국_1단계는_11개_권역과_0건을_모두_집계한다() {
        HousingComplexSearchRequest request = request(null, "30", "120", "45", "135");

        HousingMapResponse response = clusteringService.getMap(request, decimal("6.0"), null);
        List<HousingMapAggregateNodeResponse> nodes = aggregateNodes(response);

        assertEquals(1, response.resolvedStage());
        assertEquals(HousingMapRepresentation.AGGREGATE, response.representation());
        assertEquals(11, nodes.size());
        assertTrue(nodes.stream().allMatch(node -> node.uniqueComplexCount() == 0L));
    }

    @Test
    void 경기_filter는_1단계와_2단계에서_관련_상위_지역만_응답한다() {
        HousingComplexSearchRequest request = request("41", "30", "120", "45", "135");

        HousingMapResponse serviceZone = clusteringService.getMap(request, decimal("6.0"), null);
        HousingMapResponse metropolitan = clusteringService.getMap(request, decimal("8.5"), 1);

        assertEquals(List.of("SERVICE_ZONE:GYEONGGI"), aggregateGroupKeys(serviceZone));
        assertEquals(List.of("METROPOLITAN:41"), aggregateGroupKeys(metropolitan));
    }

    @Test
    void 개별_단계는_v1과_같은_filter와_viewport의_모든_지도_필드를_반환한다() {
        HousingComplex first = persistComplex("첫 단지", "41131", "37.45", "127.14");
        HousingComplex second = persistComplex("둘째 단지", "41135", "37.45", "127.14");
        persistComplex("영역 밖 단지", "41131", "36.00", "127.14");
        entityManager.flush();
        HousingComplexSearchRequest request = request(null, "37.0", "126.0", "38.0", "128.0");

        HousingComplexMapResponse legacy = legacyService.getComplexesForMap(request);
        HousingMapResponse clustering = clusteringService.getMap(request, decimal("14.0"), 3);

        assertEquals(List.of(first.getId(), second.getId()), legacyIds(legacy));
        assertEquals(expectedIndividualNodes(legacy), individualNodes(clustering));
    }

    @Test
    void 경기_filter는_31개_지역과_성남_전체_count와_0건을_함께_응답한다() {
        persistComplex("수정구 단지", "41131", "37.45", "127.14");
        persistComplex("분당구 단지", "41135", "35.00", "127.14");
        entityManager.flush();
        HousingComplexSearchRequest request = request("41", "30", "120", "45", "135");

        HousingMapResponse response = clusteringService.getMap(request, decimal("11.0"), 3);
        List<HousingMapAggregateNodeResponse> nodes = aggregateNodes(response);

        assertEquals(31, nodes.size());
        assertEquals(2L, countOf(nodes, "BASIC_REGION:41130"));
        assertEquals(0L, countOf(nodes, "BASIC_REGION:41290"));
    }

    private HousingComplex persistComplex(
            String name,
            String regionCode,
            String latitude,
            String longitude
    ) {
        HousingComplex complex = HousingComplex.create(
                name, "source-" + name, "행복주택", address(regionCode, latitude, longitude),
                100, "LH", LocalDate.of(2020, 1, 1), null, null, null,
                true, 50, null, null
        );
        entityManager.persist(complex);
        return complex;
    }

    private Address address(
            String regionCode,
            String latitude,
            String longitude
    ) {
        return Address.create(
                "경기도 성남시 두꺼비로 1", "4113110100100010000", "4113110100",
                regionCode.substring(0, 2), regionCode,
                decimal(latitude), decimal(longitude)
        );
    }

    private static HousingComplexSearchRequest request(
            String regionCode,
            String southWestLatitude,
            String southWestLongitude,
            String northEastLatitude,
            String northEastLongitude
    ) {
        return new HousingComplexSearchRequest(
                null, regionCode, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                decimal(southWestLatitude), decimal(southWestLongitude),
                decimal(northEastLatitude), decimal(northEastLongitude), null
        );
    }

    private static List<Long> legacyIds(HousingComplexMapResponse response) {
        return response.items().stream().map(item -> item.complexId()).toList();
    }

    private static List<HousingMapIndividualNodeResponse> expectedIndividualNodes(
            HousingComplexMapResponse response
    ) {
        return response.items().stream().map(HousingMapIndividualNodeResponse::new).toList();
    }

    private static List<HousingMapIndividualNodeResponse> individualNodes(HousingMapResponse response) {
        return response.nodes().stream()
                .map(HousingMapIndividualNodeResponse.class::cast)
                .toList();
    }

    private static List<HousingMapAggregateNodeResponse> aggregateNodes(HousingMapResponse response) {
        return response.nodes().stream()
                .map(HousingMapAggregateNodeResponse.class::cast)
                .toList();
    }

    private static List<String> aggregateGroupKeys(HousingMapResponse response) {
        return aggregateNodes(response).stream()
                .map(HousingMapAggregateNodeResponse::groupKey)
                .toList();
    }

    private static long countOf(
            List<HousingMapAggregateNodeResponse> nodes,
            String groupKey
    ) {
        return nodes.stream()
                .filter(node -> node.groupKey().equals(groupKey))
                .findFirst()
                .orElseThrow()
                .uniqueComplexCount();
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
