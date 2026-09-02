package com.toadzip.backend.housing.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MapClusteringZoomPolicyTest {

    @ParameterizedTest
    @CsvSource({
            "0, SERVICE_ZONE",
            "7.49, SERVICE_ZONE",
            "7.50, METROPOLITAN",
            "9.99, METROPOLITAN",
            "10.00, BASIC_REGION",
            "12.99, BASIC_REGION",
            "13.00, INDIVIDUAL",
            "25.00, INDIVIDUAL"
    })
    void 이전_단계가_없으면_기본_zoom_경계로_단계를_결정한다(
            String zoom,
            MapClusteringStage expected
    ) {
        assertEquals(expected, policy().resolveStage(decimal(zoom), null));
    }

    @ParameterizedTest
    @CsvSource({
            "SERVICE_ZONE, 7.69, SERVICE_ZONE",
            "SERVICE_ZONE, 7.70, METROPOLITAN",
            "METROPOLITAN, 7.30, METROPOLITAN",
            "METROPOLITAN, 7.29, SERVICE_ZONE",
            "METROPOLITAN, 10.19, METROPOLITAN",
            "METROPOLITAN, 10.20, BASIC_REGION",
            "BASIC_REGION, 9.80, BASIC_REGION",
            "BASIC_REGION, 9.79, METROPOLITAN",
            "BASIC_REGION, 13.19, BASIC_REGION",
            "BASIC_REGION, 13.20, INDIVIDUAL",
            "INDIVIDUAL, 12.80, INDIVIDUAL",
            "INDIVIDUAL, 12.79, BASIC_REGION"
    })
    void 인접_단계의_완충_구간에서는_직전_단계를_유지한다(
            MapClusteringStage previous,
            String zoom,
            MapClusteringStage expected
    ) {
        assertEquals(expected, policy().resolveStage(decimal(zoom), previous));
    }

    @ParameterizedTest
    @CsvSource({
            "SERVICE_ZONE, 10.00, BASIC_REGION",
            "SERVICE_ZONE, 13.00, INDIVIDUAL",
            "INDIVIDUAL, 9.00, METROPOLITAN",
            "INDIVIDUAL, 7.00, SERVICE_ZONE"
    })
    void zoom을_크게_바꾸면_중간_단계를_강제하지_않는다(
            MapClusteringStage previous,
            String zoom,
            MapClusteringStage expected
    ) {
        assertEquals(expected, policy().resolveStage(decimal(zoom), previous));
    }

    @Test
    void 단계별_클릭_zoom은_정확히_다음_단계의_안전_구간에_있다() {
        MapClusteringZoomPolicy policy = policy();

        assertEquals(Optional.of(decimal("8.50")), policy.expansionZoom(MapClusteringStage.SERVICE_ZONE));
        assertEquals(Optional.of(decimal("11.00")), policy.expansionZoom(MapClusteringStage.METROPOLITAN));
        assertEquals(Optional.of(decimal("14.00")), policy.expansionZoom(MapClusteringStage.BASIC_REGION));
        assertEquals(Optional.empty(), policy.expansionZoom(MapClusteringStage.INDIVIDUAL));
    }

    @Test
    void 잘못된_zoom을_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> policy().resolveStage(decimal("-0.01"), null));
        assertThrows(IllegalArgumentException.class, () -> policy().resolveStage(null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.20", "0.30"})
    void hysteresis가_경계와_같거나_더_크면_전환을_생성하지_않는다(String hysteresis) {
        assertThrows(IllegalArgumentException.class, () -> new MapClusteringTransition(
                MapClusteringStage.SERVICE_ZONE,
                MapClusteringStage.METROPOLITAN,
                decimal("0.20"),
                decimal(hysteresis),
                decimal("1.00")
        ));
    }

    @Test
    void 모든_인접_단계의_전환이_없으면_정책을_생성하지_않는다() {
        List<MapClusteringTransition> incomplete = transitions().subList(0, 2);

        assertThrows(
                IllegalArgumentException.class,
                () -> MapClusteringZoomPolicy.of(version(), incomplete)
        );
    }

    @Test
    void 클릭_zoom이_다음_단계를_건너뛰면_정책을_생성하지_않는다() {
        List<MapClusteringTransition> skippingExpansion = List.of(
                transition(MapClusteringStage.SERVICE_ZONE, MapClusteringStage.METROPOLITAN, "7.50", "13.00"),
                transition(MapClusteringStage.METROPOLITAN, MapClusteringStage.BASIC_REGION, "10.00", "11.00"),
                transition(MapClusteringStage.BASIC_REGION, MapClusteringStage.INDIVIDUAL, "13.00", "14.00")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> MapClusteringZoomPolicy.of(version(), skippingExpansion)
        );
    }

    @Test
    void 인접한_완충_구간이_겹치면_정책을_생성하지_않는다() {
        List<MapClusteringTransition> overlapping = List.of(
                transition(MapClusteringStage.SERVICE_ZONE, MapClusteringStage.METROPOLITAN, "9.90", "10.10"),
                transition(MapClusteringStage.METROPOLITAN, MapClusteringStage.BASIC_REGION, "10.00", "11.00"),
                transition(MapClusteringStage.BASIC_REGION, MapClusteringStage.INDIVIDUAL, "13.00", "14.00")
        );

        assertThrows(IllegalArgumentException.class, () -> MapClusteringZoomPolicy.of(version(), overlapping));
    }

    private static MapClusteringZoomPolicy policy() {
        return MapClusteringZoomPolicy.of(version(), transitions());
    }

    private static MapClusteringPolicyVersion version() {
        return new MapClusteringPolicyVersion("2026-09-02-v1", "2026-07-01");
    }

    private static List<MapClusteringTransition> transitions() {
        return List.of(
                transition(MapClusteringStage.SERVICE_ZONE, MapClusteringStage.METROPOLITAN, "7.50", "8.50"),
                transition(MapClusteringStage.METROPOLITAN, MapClusteringStage.BASIC_REGION, "10.00", "11.00"),
                transition(MapClusteringStage.BASIC_REGION, MapClusteringStage.INDIVIDUAL, "13.00", "14.00")
        );
    }

    private static MapClusteringTransition transition(
            MapClusteringStage from,
            MapClusteringStage to,
            String boundaryZoom,
            String expansionZoom
    ) {
        return new MapClusteringTransition(from, to, decimal(boundaryZoom), decimal("0.20"), decimal(expansionZoom));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
