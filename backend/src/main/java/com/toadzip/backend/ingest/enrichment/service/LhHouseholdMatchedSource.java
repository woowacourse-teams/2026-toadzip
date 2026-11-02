package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdSourceMapper.LhHousingTypeHouseholdSource;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdSourceMapper.LhHousingTypeHouseholdSourceKey;

record LhHouseholdMatchedSource(
        LhHousingTypeHouseholdSourceKey sourceKey,
        HousingComplex complex,
        LhHousingTypeHouseholdSource source
) {
}
