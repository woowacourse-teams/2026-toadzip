package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import com.toadzip.backend.ingest.domain.SupplyNameNormalizer;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexSourceMapper.MyHomeHousingTypeMappingData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 단지 하나의 주택형을 들어온 마이홈 주택형에 맞춘다. 같은 원천 식별자는 갱신하고, 식별자가 바뀐 같은 원천 주택형과
 * 관리자가 만든 주택형은 이어받으며, 원천에서 사라진 주택형은 공급행·저장 매칭·관리자 수정이 참조하지 않을 때만 지운다.
 */
@Component
@RequiredArgsConstructor
class MyHomeHousingTypeSynchronizer {

    private final HousingTypeRepository housingTypeRepository;
    private final SupplyRowRepository supplyRowRepository;
    private final SavedSupplyMatches savedMatches;

    /** 분할 단지 원천을 합칠 때 이름·면적이 같은 주택형은 이미 저장된 원천 식별자를 우선해 하나만 남긴다. */
    List<MyHomeHousingTypeMappingData> distinct(
            HousingComplex complex, List<MyHomeHousingTypeMappingData> incoming
    ) {
        Set<String> stored = housingTypeRepository.findAllIncludingTrashByHousingComplexId(complex.getId()).stream()
                .map(HousingType::getSourceHousingTypeIdentifier).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<List<Object>, MyHomeHousingTypeMappingData> distinct = new LinkedHashMap<>();
        for (MyHomeHousingTypeMappingData type : incoming) {
            List<Object> key = Arrays.asList(type.name(), type.exclusiveArea().stripTrailingZeros(),
                    type.supplyArea() == null ? null : type.supplyArea().stripTrailingZeros());
            MyHomeHousingTypeMappingData chosen = distinct.get(key);
            if (chosen == null || !stored.contains(chosen.sourceHousingTypeIdentifier())
                    && stored.contains(type.sourceHousingTypeIdentifier())) {
                distinct.put(key, type);
            }
        }
        return List.copyOf(distinct.values());
    }

    /** 원천에서 사라진 주택형 가운데 보강값이 있는 것은 {@code preserveSupplementalValues}면 지우지 않는다. */
    Result synchronize(
            HousingComplex complex,
            List<MyHomeHousingTypeMappingData> incoming,
            boolean preserveSupplementalValues
    ) {
        Map<String, HousingType> remainingStoredByIdentifier = housingTypeRepository.findAllIncludingTrashByHousingComplexId(complex.getId())
                .stream()
                .filter(type -> type.getSourceHousingTypeIdentifier() != null)
                .collect(Collectors.toMap(
                        HousingType::getSourceHousingTypeIdentifier,
                        Function.identity(),
                        (first, second) -> first,
                        LinkedHashMap::new
                ));
        MatchedHousingTypes matched = updateMatchedHousingTypes(remainingStoredByIdentifier, incoming);
        Result unmatched = writeUnmatchedHousingTypes(
                complex, remainingStoredByIdentifier, matched.unmatched()
        );
        int deleted = deleteStaleHousingTypes(remainingStoredByIdentifier, preserveSupplementalValues);
        return new Result(
                unmatched.created(), matched.updated() + unmatched.updated(), matched.unchanged(), deleted
        );
    }

    private MatchedHousingTypes updateMatchedHousingTypes(
            Map<String, HousingType> remainingStoredByIdentifier,
            List<MyHomeHousingTypeMappingData> incoming
    ) {
        int updated = 0;
        int unchanged = 0;
        List<MyHomeHousingTypeMappingData> unmatchedIncoming = new ArrayList<>();
        for (MyHomeHousingTypeMappingData data : incoming) {
            HousingType housingType = remainingStoredByIdentifier.remove(data.sourceHousingTypeIdentifier());
            if (housingType == null) {
                unmatchedIncoming.add(data);
                continue;
            }
            boolean typeUpdated = housingType.updateFromMyHome(
                    data.sourceHousingTypeIdentifier(),
                    data.name(),
                    data.exclusiveArea(),
                    data.supplyArea()
            );
            boolean priceUpdated = housingType.updateBasicRentalCondition(data.basicRentalCondition());
            if (typeUpdated || priceUpdated) {
                updated++;
                continue;
            }
            unchanged++;
        }
        return new MatchedHousingTypes(unmatchedIncoming, updated, unchanged);
    }

    private Result writeUnmatchedHousingTypes(
            HousingComplex complex,
            Map<String, HousingType> remainingStoredByIdentifier,
            List<MyHomeHousingTypeMappingData> unmatchedIncoming
    ) {
        int created = 0;
        int updated = 0;
        for (MyHomeHousingTypeMappingData data : unmatchedIncoming) {
            HousingType corrected = findUniqueStoredTypeByName(remainingStoredByIdentifier, data);
            HousingType adminCreated = corrected == null ? adminCreatedType(remainingStoredByIdentifier, data) : null;
            if (adminCreated != null) {
                // 관리자가 공고문으로 만든 주택형을 이어받아 같은 주택형이 두 번 생기지 않게 한다.
                remainingStoredByIdentifier.remove(adminCreated.getSourceHousingTypeIdentifier());
                adminCreated.adoptMyHomeSource(data.sourceHousingTypeIdentifier(), data.supplyArea());
                adminCreated.updateBasicRentalCondition(data.basicRentalCondition());
                updated++;
                continue;
            }
            if (corrected == null) {
                HousingType createdType = HousingType.createFromMyHome(
                        complex,
                        data.sourceHousingTypeIdentifier(),
                        data.name(),
                        data.exclusiveArea(),
                        data.supplyArea()
                );
                createdType.updateBasicRentalCondition(data.basicRentalCondition());
                housingTypeRepository.save(createdType);
                created++;
                continue;
            }
            remainingStoredByIdentifier.remove(corrected.getSourceHousingTypeIdentifier());
            corrected.updateFromMyHome(
                    data.sourceHousingTypeIdentifier(),
                    data.name(),
                    data.exclusiveArea(),
                    data.supplyArea()
            );
            corrected.updateBasicRentalCondition(data.basicRentalCondition());
            updated++;
        }
        return new Result(created, updated, 0, 0);
    }

    private int deleteStaleHousingTypes(
            Map<String, HousingType> remainingStoredByIdentifier,
            boolean preserveSupplementalValues
    ) {
        List<HousingType> stale = List.copyOf(remainingStoredByIdentifier.values());
        List<HousingType> deletable = stale.stream()
                .filter(type -> !type.isAdminModified())
                .filter(type -> !supplyRowRepository.existsByHousingType(type))
                .filter(type -> !savedMatches.references(type))
                .filter(type -> !preserveSupplementalValues || !type.hasSupplementalInformation())
                .toList();
        housingTypeRepository.deleteAll(deletable);
        return deletable.size();
    }

    /** 관리자가 만든 주택형 중 같은 이름, 없으면 같은 전용면적이 하나뿐인 주택형 */
    private HousingType adminCreatedType(Map<String, HousingType> remainingStoredByIdentifier, MyHomeHousingTypeMappingData incoming) {
        List<HousingType> adminCreated = remainingStoredByIdentifier.values().stream().filter(HousingType::isAdminCreated).toList();
        List<HousingType> sameName = adminCreated.stream()
                .filter(type -> SupplyNameNormalizer.sameHousingType(type.getName(), incoming.name())).toList();
        if (sameName.size() == 1) {
            return sameName.getFirst();
        }
        List<HousingType> sameArea = adminCreated.stream()
                .filter(type -> type.getExclusiveArea().compareTo(incoming.exclusiveArea()) == 0).toList();
        return sameName.isEmpty() && sameArea.size() == 1 ? sameArea.getFirst() : null;
    }

    private HousingType findUniqueStoredTypeByName(
            Map<String, HousingType> remainingStoredByIdentifier,
            MyHomeHousingTypeMappingData incoming
    ) {
        List<HousingType> sameName = remainingStoredByIdentifier.values()
                .stream()
                .filter(type -> type.getName().equals(incoming.name()))
                // 관리자가 만든 주택형은 마이홈 원천 식별자 형식이 아니라 같은 원천 비교에서 뺀다(adminCreatedType에서 이어받는다).
                .filter(type -> !type.isAdminCreated())
                .filter(type -> MyHomeComplexSourceView.sameSourceComplex(
                        type.getSourceHousingTypeIdentifier(), incoming.sourceHousingTypeIdentifier()))
                .toList();
        if (sameName.size() != 1) {
            return null;
        }
        return sameName.getFirst();
    }

    private record MatchedHousingTypes(List<MyHomeHousingTypeMappingData> unmatched, int updated, int unchanged) {
    }

    record Result(int created, int updated, int unchanged, int deleted) {
    }
}
