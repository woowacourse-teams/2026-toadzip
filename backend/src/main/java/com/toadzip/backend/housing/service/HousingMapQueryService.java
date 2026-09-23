package com.toadzip.backend.housing.service;

import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.domain.MapClusteringStage;
import com.toadzip.backend.housing.domain.MapClusteringZoomPolicy;
import com.toadzip.backend.housing.dto.request.HousingComplexSearchRequest;
import com.toadzip.backend.housing.dto.response.HousingMapResponse;
import com.toadzip.backend.housing.exception.InvalidComplexRequestException;
import com.toadzip.backend.housing.repository.HousingComplexFilterCondition;
import com.toadzip.backend.housing.repository.MapClusteringZoomPolicyRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HousingMapQueryService {

    private final HousingComplexSearchRequestNormalizer requestNormalizer;
    private final MapClusteringZoomPolicyRepository zoomPolicyRepository;
    private final HousingMapResponseFactory responseFactory;

    HousingMapQueryService(
            HousingComplexSearchRequestNormalizer requestNormalizer,
            MapClusteringZoomPolicyRepository zoomPolicyRepository,
            HousingMapResponseFactory responseFactory
    ) {
        this.requestNormalizer = requestNormalizer;
        this.zoomPolicyRepository = zoomPolicyRepository;
        this.responseFactory = responseFactory;
    }

    @Transactional(readOnly = true)
    public HousingMapResponse getMap(
            HousingComplexSearchRequest request,
            BigDecimal zoom,
            Integer previousResolvedStage
    ) {
        MapBounds bounds = requestNormalizer.normalizeBounds(request);
        HousingComplexFilterCondition filters = requestNormalizer.normalizeFilters(request);
        MapClusteringZoomPolicy zoomPolicy = zoomPolicyRepository.current();
        MapClusteringStage stage = resolveStage(zoomPolicy, zoom, previousResolvedStage);
        HousingMapNodeResult result = responseFactory.create(stage, bounds, filters, zoomPolicy);
        return response(stage, zoomPolicy, result);
    }

    private MapClusteringStage resolveStage(
            MapClusteringZoomPolicy policy,
            BigDecimal zoom,
            Integer previousResolvedStage
    ) {
        requireZoom(zoom);
        return policy.resolveStage(zoom, previousStage(previousResolvedStage));
    }

    private void requireZoom(BigDecimal zoom) {
        if (zoom != null && zoom.signum() >= 0) {
            return;
        }
        throw new InvalidComplexRequestException();
    }

    private MapClusteringStage previousStage(Integer stage) {
        if (stage == null) {
            return null;
        }
        try {
            return MapClusteringStage.fromNumber(stage);
        } catch (IllegalArgumentException exception) {
            throw new InvalidComplexRequestException();
        }
    }

    private HousingMapResponse response(
            MapClusteringStage stage,
            MapClusteringZoomPolicy policy,
            HousingMapNodeResult result
    ) {
        return new HousingMapResponse(
                stage.number(), result.representation(), policy.policyVersion(),
                policy.regionDatasetVersion(), result.nodes()
        );
    }
}
