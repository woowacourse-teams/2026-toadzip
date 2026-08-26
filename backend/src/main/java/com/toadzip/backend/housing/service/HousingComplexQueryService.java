package com.toadzip.backend.housing.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.housing.dto.response.HousingComplexMapResponse;
import com.toadzip.backend.housing.repository.ComplexSummaryQueryRepository;

@Service
public class HousingComplexQueryService {

    private final ComplexSummaryQueryRepository repository;

    private final HousingComplexSummaryMapper summaryMapper;

    public HousingComplexQueryService(
            ComplexSummaryQueryRepository repository,
            HousingComplexSummaryMapper summaryMapper
    ) {
        this.repository = repository;
        this.summaryMapper = summaryMapper;
    }

    @Transactional(readOnly = true)
    public HousingComplexMapResponse getComplexesForMap(MapBounds bounds) {
        return new HousingComplexMapResponse(repository.findAllInBounds(bounds).stream()
                .map(summaryMapper::toMapItem)
                .toList());
    }
}
