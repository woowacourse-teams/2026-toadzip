package com.toadzip.backend.ingest.enrichment.service;

import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSource;
import com.toadzip.backend.ingest.domain.SourceArea;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LhHousingTypeHouseholdSourceMapper {

    public Map<LhHousingTypeHouseholdSourceKey, List<LhCatalogSource>> group(
            List<LhCatalogSource> sources
    ) {
        Map<LhHousingTypeHouseholdSourceKey, List<LhCatalogSource>> grouped = new LinkedHashMap<>();
        for (LhCatalogSource source : sources) {
            LhHousingTypeHouseholdSourceKey key = new LhHousingTypeHouseholdSourceKey(
                    LhCatalogNames.region(source.getAreaName()),
                    LhCatalogNames.normalized(source.getSupplyTypeName()),
                    LhCatalogNames.normalized(source.getComplexLabel())
            );
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(source);
        }
        return grouped;
    }

    public LhHousingTypeHouseholdSource map(List<LhCatalogSource> sources) {
        LhCatalogSource first = sources.getFirst();
        String areaName = required(first.getAreaName(), "지역명");
        String supplyTypeName = required(first.getSupplyTypeName(), "공급유형");
        String complexName = required(first.getComplexLabel(), "단지명");
        int totalHouseholdCount = sameComplexHouseholdCount(sources);
        return new LhHousingTypeHouseholdSource(
                areaName,
                supplyTypeName,
                complexName,
                totalHouseholdCount,
                housingTypes(sources)
        );
    }

    private int sameComplexHouseholdCount(List<LhCatalogSource> sources) {
        Integer totalHouseholdCount = null;
        for (LhCatalogSource source : sources) {
            int current = nonNegativeInteger(source.getComplexTotalUnitCount(), "단지 전체 세대수");
            if (totalHouseholdCount != null && totalHouseholdCount != current) {
                throw new IllegalArgumentException("단지 전체 세대수가 서로 다릅니다.");
            }
            totalHouseholdCount = current;
        }
        return totalHouseholdCount;
    }

    private List<LhHousingTypeHousehold> housingTypes(List<LhCatalogSource> sources) {
        Map<BigDecimal, Integer> householdCountsByArea = new LinkedHashMap<>();
        for (LhCatalogSource source : sources) {
            BigDecimal area = area(source.getExclusiveArea());
            int householdCount = nonNegativeInteger(source.getTotalUnitCount(), "주택형 세대수");
            Integer previous = householdCountsByArea.putIfAbsent(area, householdCount);
            if (previous != null && previous != householdCount) {
                throw new IllegalArgumentException(
                        "같은 전용면적의 주택형 세대수가 서로 다릅니다."
                );
            }
        }
        return householdCountsByArea.entrySet().stream()
                .map(entry -> new LhHousingTypeHousehold(entry.getKey(), entry.getValue()))
                .toList();
    }

    private BigDecimal area(String raw) {
        try {
            BigDecimal value = new BigDecimal(required(raw, "전용면적"));
            if (value.signum() < 0) {
                throw new IllegalArgumentException("전용면적은 음수일 수 없습니다.");
            }
            return SourceArea.normalized(value);
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException("전용면적이 숫자가 아닙니다.");
        }
    }

    private int nonNegativeInteger(String raw, String fieldName) {
        try {
            int value = Integer.parseInt(required(raw, fieldName).replace(",", ""));
            if (value < 0) {
                throw new IllegalArgumentException(fieldName + "는 음수일 수 없습니다.");
            }
            return value;
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + "가 정수가 아닙니다.");
        }
    }

    private String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "이 없습니다.");
        }
        return value.strip();
    }

    record LhHousingTypeHouseholdSourceKey(String areaName, String supplyTypeName, String complexName) {

        String failureKey() {
            return component(areaName) + component(supplyTypeName) + component(complexName);
        }

        private String component(String value) {
            return value.length() + ":" + value;
        }
    }

    record LhHousingTypeHouseholdSource(
            String areaName,
            String supplyTypeName,
            String complexName,
            int totalHouseholdCount,
            List<LhHousingTypeHousehold> housingTypes
    ) {
    }

    record LhHousingTypeHousehold(BigDecimal exclusiveArea, int totalHouseholdCount) {
    }
}
