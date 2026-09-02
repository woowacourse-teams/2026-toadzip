package com.toadzip.backend.ingest.service;

import com.toadzip.backend.ingest.domain.LhCatalogSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
                    canonicalRegion(source.getAreaName()),
                    normalized(source.getSupplyTypeName()),
                    normalized(source.getComplexLabel())
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
            return value.setScale(4, RoundingMode.HALF_UP);
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

    private String normalized(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\s\\-·,()]", "").strip().toLowerCase();
    }

    private String canonicalRegion(String value) {
        return normalized(value)
                .replace("서울특별시", "서울")
                .replace("부산광역시", "부산")
                .replace("대구광역시", "대구")
                .replace("인천광역시", "인천")
                .replace("광주광역시", "광주")
                .replace("대전광역시", "대전")
                .replace("울산광역시", "울산")
                .replace("세종특별자치시", "세종")
                .replace("강원특별자치도", "강원")
                .replace("강원도", "강원")
                .replace("전북특별자치도", "전북")
                .replace("전라북도", "전북")
                .replace("제주특별자치도", "제주")
                .replace("경기도", "경기")
                .replace("충청북도", "충북")
                .replace("충청남도", "충남")
                .replace("전라남도", "전남")
                .replace("경상북도", "경북")
                .replace("경상남도", "경남");
    }
}

record LhHousingTypeHouseholdSourceKey(String areaName, String supplyTypeName, String complexName) {
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
