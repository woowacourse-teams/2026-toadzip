package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.domain.SupplyNameNormalizer;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
class MyHomeAnnouncementSupplyMatcher {

    private final HousingComplexRepository housingComplexRepository;
    private final HousingTypeRepository housingTypeRepository;

    MyHomeAnnouncementSupplyMatcher(
            HousingComplexRepository housingComplexRepository,
            HousingTypeRepository housingTypeRepository
    ) {
        this.housingComplexRepository = housingComplexRepository;
        this.housingTypeRepository = housingTypeRepository;
    }

    MyHomeSupplyMatchResult match(MyHomeSupplyRowMappingData data) {
        List<HousingComplex> complexes = housingComplexRepository.findAllByPnuAndSupplyType(
                data.pnu(),
                data.complexSupplyType()
        );
        if (complexes.isEmpty()) {
            return MyHomeSupplyMatchResult.failure(
                    data,
                    MyHomeAnnouncementMappingFailureReason.COMPLEX_NOT_FOUND,
                    "PNU와 공급유형이 일치하는 단지가 없습니다."
            );
        }
        if (complexes.size() > 1) {
            HousingComplex matched = uniqueComplexByName(complexes, data.sourceComplexName());
            if (matched == null) {
                return MyHomeSupplyMatchResult.failure(
                        data,
                        MyHomeAnnouncementMappingFailureReason.AMBIGUOUS_COMPLEX,
                        "PNU와 공급유형이 일치하는 단지를 "
                                + "단지명으로도 하나로 확정할 수 없습니다."
                );
            }
            return matchHousingType(data, matched);
        }
        return matchHousingType(data, complexes.getFirst());
    }

    private HousingComplex uniqueComplexByName(List<HousingComplex> complexes, String sourceName) {
        List<HousingComplex> matched = complexes.stream()
                .filter(complex -> SupplyNameNormalizer.sameComplex(complex.getName(), sourceName))
                .toList();
        if (matched.size() != 1) {
            return null;
        }
        return matched.getFirst();
    }

    private MyHomeSupplyMatchResult matchHousingType(MyHomeSupplyRowMappingData data, HousingComplex complex) {
        List<HousingType> housingTypes = housingTypeRepository.findAllByHousingComplex(complex);
        if (housingTypes.isEmpty()) {
            return MyHomeSupplyMatchResult.failure(
                    data,
                    complex,
                    MyHomeAnnouncementMappingFailureReason.HOUSING_TYPE_NOT_FOUND,
                    "단지에 연결된 주택형이 없습니다."
            );
        }
        HousingType matched = uniqueHousingTypeByName(housingTypes, data.sourceHousingTypeName());
        if (matched == null) {
            matched = uniqueHousingTypeByExclusiveArea(housingTypes, data.exclusiveArea());
        }
        if (matched == null) {
            matched = uniqueHousingTypeBySupplyArea(housingTypes, data.supplyArea());
        }
        if (matched == null) {
            return MyHomeSupplyMatchResult.failure(
                    data,
                    complex,
                    MyHomeAnnouncementMappingFailureReason.AMBIGUOUS_HOUSING_TYPE,
                    "원천 주택형명과 면적으로도 주택형 하나를 확정할 수 없습니다."
            );
        }
        return MyHomeSupplyMatchResult.matched(complex, matched);
    }

    private HousingType uniqueHousingTypeByName(List<HousingType> housingTypes, String sourceName) {
        List<HousingType> matched = housingTypes.stream()
                .filter(housingType -> SupplyNameNormalizer.sameHousingType(housingType.getName(), sourceName))
                .toList();
        if (matched.size() != 1) {
            return null;
        }
        return matched.getFirst();
    }

    private HousingType uniqueHousingTypeByExclusiveArea(List<HousingType> housingTypes, BigDecimal exclusiveArea) {
        if (exclusiveArea == null) {
            return null;
        }
        return uniqueHousingTypeByArea(housingTypes, HousingType::getExclusiveArea, exclusiveArea);
    }

    private HousingType uniqueHousingTypeBySupplyArea(List<HousingType> housingTypes, BigDecimal supplyArea) {
        if (supplyArea == null) {
            return null;
        }
        return uniqueHousingTypeByArea(housingTypes, HousingType::getSupplyArea, supplyArea);
    }

    private HousingType uniqueHousingTypeByArea(
            List<HousingType> housingTypes,
            Function<HousingType, BigDecimal> areaExtractor,
            BigDecimal sourceArea
    ) {
        List<HousingType> matched = housingTypes.stream()
                .filter(housingType -> sameArea(areaExtractor.apply(housingType), sourceArea))
                .toList();
        if (matched.size() != 1) {
            return null;
        }
        return matched.getFirst();
    }

    private boolean sameArea(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }
}

record MyHomeSupplyMatchResult(
        HousingComplex complex,
        HousingType housingType,
        MyHomeSupplyMatchingFailureData failure
) {

    static MyHomeSupplyMatchResult matched(HousingComplex complex, HousingType housingType) {
        return new MyHomeSupplyMatchResult(complex, housingType, null);
    }

    static MyHomeSupplyMatchResult failure(
            MyHomeSupplyRowMappingData data,
            MyHomeAnnouncementMappingFailureReason reason,
            String detail
    ) {
        return failure(data, null, reason, detail);
    }

    static MyHomeSupplyMatchResult failure(
            MyHomeSupplyRowMappingData data,
            HousingComplex complex,
            MyHomeAnnouncementMappingFailureReason reason,
            String detail
    ) {
        return new MyHomeSupplyMatchResult(
                complex,
                null,
                new MyHomeSupplyMatchingFailureData(data.source(), reason, detail)
        );
    }

    String failureDetail() {
        if (failure == null) {
            return null;
        }
        return failure.detail();
    }
}
