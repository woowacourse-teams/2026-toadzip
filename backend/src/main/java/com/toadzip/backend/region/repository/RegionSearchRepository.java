package com.toadzip.backend.region.repository;

import java.util.List;

public interface RegionSearchRepository {

    List<RegionSearchResult> findByKeyword(String normalizedKeyword);
}
