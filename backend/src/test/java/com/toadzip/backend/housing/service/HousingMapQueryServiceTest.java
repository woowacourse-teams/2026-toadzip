package com.toadzip.backend.housing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.housing.domain.MapClusteringStage;
import com.toadzip.backend.housing.dto.request.HousingComplexSearchRequest;
import com.toadzip.backend.housing.dto.response.HousingMapAggregateNodeResponse;
import com.toadzip.backend.housing.dto.response.HousingMapRepresentation;
import com.toadzip.backend.housing.dto.response.HousingMapResponse;
import com.toadzip.backend.housing.exception.InvalidComplexRequestException;
import com.toadzip.backend.housing.repository.CsvMapClusteringZoomPolicyRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

@ExtendWith(MockitoExtension.class)
class HousingMapQueryServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC
    );

    @Mock
    private HousingMapResponseFactory responseFactory;

    private HousingMapQueryService service;

    @BeforeEach
    void setUp() {
        HousingComplexSearchRequestNormalizer normalizer =
                new HousingComplexSearchRequestNormalizer((province, district) -> java.util.Optional.empty(), CLOCK);
        CsvMapClusteringZoomPolicyRepository zoomRepository =
                new CsvMapClusteringZoomPolicyRepository(
                        new ClassPathResource("map-clustering/stage-transitions.csv")
                );
        service = new HousingMapQueryService(normalizer, zoomRepository, responseFactory);
    }

    @Test
    void zoom과_직전_단계로_hysteresis를_적용하고_정책_version을_응답한다() {
        HousingMapAggregateNodeResponse node = new HousingMapAggregateNodeResponse(
                "METROPOLITAN:11", "서울", decimal("37.55"), decimal("126.99"),
                42L, 3, decimal("11.00")
        );
        when(responseFactory.create(eq(MapClusteringStage.METROPOLITAN), any(), any(), any()))
                .thenReturn(new HousingMapNodeResult(HousingMapRepresentation.AGGREGATE, List.of(node)));

        HousingMapResponse response = service.getMap(request(), decimal("10.10"), 2);

        assertEquals(2, response.resolvedStage());
        assertEquals("AGGREGATE", response.representation().name());
        assertEquals("2026-09-02-v1", response.policyVersion());
        assertEquals("2026-07-01", response.regionDatasetVersion());
        assertEquals(List.of(node), response.nodes());
    }

    @Test
    void 범위를_벗어난_직전_단계를_repository_호출_전에_거부한다() {
        assertThrows(
                InvalidComplexRequestException.class,
                () -> service.getMap(request(), decimal("10.10"), 5)
        );

        verify(responseFactory, never()).create(any(), any(), any(), any());
    }

    @Test
    void 음수_zoom을_repository_호출_전에_거부한다() {
        assertThrows(
                InvalidComplexRequestException.class,
                () -> service.getMap(request(), decimal("-0.01"), null)
        );

        verify(responseFactory, never()).create(any(), any(), any(), any());
    }

    private static HousingComplexSearchRequest request() {
        return new HousingComplexSearchRequest(
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                decimal("37.0"), decimal("126.0"), decimal("38.0"), decimal("128.0"), null
        );
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
