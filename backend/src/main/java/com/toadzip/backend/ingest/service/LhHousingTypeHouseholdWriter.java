package com.toadzip.backend.ingest.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.dto.LhHousingTypeHouseholdEnrichmentReport;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LhHousingTypeHouseholdWriter {

    private final HousingTypeRepository housingTypeRepository;

    public LhHousingTypeHouseholdWriter(HousingTypeRepository housingTypeRepository) {
        this.housingTypeRepository = housingTypeRepository;
    }

    public LhHousingTypeHouseholdEnrichmentReport write(
            HousingComplex complex,
            List<LhHousingTypeHousehold> sources
    ) {
        List<HousingType> housingTypes = housingTypeRepository.findAllByHousingComplex(complex);
        int updatedCount = 0;
        int unchangedCount = 0;
        int unmatchedCount = 0;
        for (LhHousingTypeHousehold source : sources) {
            List<HousingType> matches = housingTypes.stream()
                    .filter(type -> type.getExclusiveArea().compareTo(source.exclusiveArea()) == 0)
                    .toList();
            if (matches.size() != 1) {
                unmatchedCount++;
                continue;
            }
            if (matches.getFirst().enrichHouseholdCountFromLh(source.totalHouseholdCount())) {
                updatedCount++;
                continue;
            }
            unchangedCount++;
        }
        return LhHousingTypeHouseholdEnrichmentReport.matched(
                updatedCount, unchangedCount, unmatchedCount
        );
    }
}
