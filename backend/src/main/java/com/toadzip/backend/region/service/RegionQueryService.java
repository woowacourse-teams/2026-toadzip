package com.toadzip.backend.region.service;

import com.toadzip.backend.region.dto.response.RegionSearchItemResponse;
import com.toadzip.backend.region.dto.response.RegionSearchResponse;
import com.toadzip.backend.region.repository.RegionSearchRepository;
import org.springframework.stereotype.Service;

@Service
public class RegionQueryService {

    private final RegionSearchRepository regionSearchRepository;

    public RegionQueryService(RegionSearchRepository regionSearchRepository) {
        this.regionSearchRepository = regionSearchRepository;
    }

    public RegionSearchResponse searchRegions(String keyword) {
        return new RegionSearchResponse(regionSearchRepository.findByKeyword(keyword.strip())
                .stream()
                .map(region -> new RegionSearchItemResponse(
                        region.regionCode(),
                        region.provinceName(),
                        region.districtName(),
                        region.displayName()
                ))
                .toList());
    }
}
