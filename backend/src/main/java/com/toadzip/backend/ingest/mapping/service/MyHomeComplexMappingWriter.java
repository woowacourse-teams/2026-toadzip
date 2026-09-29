package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.collection.repository.MyHomeComplexSourceRepository;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexLink;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexLinkRepository;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexSourceMapper.MyHomeComplexMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexSourceMapper.MyHomeComplexMappingRejectedException;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexSourceMapper.MyHomeHousingTypeMappingData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MyHomeComplexMappingWriter {

    private final HousingComplexRepository complexRepository;

    private final HousingTypeRepository housingTypeRepository;

    private final SupplyRowRepository supplyRowRepository;
    private final MyHomeComplexLinkRepository linkRepository;
    private final MyHomeComplexSourceRepository sourceRepository;
    private final MyHomeComplexSourceMapper sourceMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MyHomeComplexMappingReport write(MyHomeComplexMappingData data, Address address) {
        MyHomeComplexLink link = linkRepository.findById(data.sourceComplexIdentifier()).orElse(null);
        if (link != null && link.getMergeId() != null) {
            throw mergedSourceConflict("확인된 원천 연결이 변경되어 재정제를 보류합니다.");
        }
        rejectChangedVerifiedSupplyType(data.sourceComplexIdentifier());
        ComplexWriteResult complexResult = upsertComplex(data, address);
        if (link == null) {
            linkRepository.save(MyHomeComplexLink.connect(data.sourceComplexIdentifier(), complexResult.complex()));
        }
        HousingTypeWriteResult housingTypeResult = synchronizeHousingTypes(
                complexResult.complex(),
                data.housingTypes()
        );
        return new MyHomeComplexMappingReport(
                complexResult.created(),
                complexResult.updated(),
                complexResult.unchanged(),
                housingTypeResult.created(),
                housingTypeResult.updated(),
                housingTypeResult.unchanged(),
                housingTypeResult.deleted(),
                0
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MyHomeComplexMappingReport writeVerified(String identifier) {
        MyHomeComplexLink link = linkRepository.findById(identifier).orElse(null);
        if (link == null || link.getMergeId() == null) {
            throw mergedSourceConflict("확인된 원천 연결이 변경되어 재정제를 보류합니다.");
        }
        return writeVerifiedGroup(link);
    }

    private ComplexWriteResult upsertComplex(MyHomeComplexMappingData data, Address address) {
        HousingComplex complex = complexRepository
                .findBySourceComplexIdentifier(data.sourceComplexIdentifier())
                .orElse(null);
        if (complex == null) {
            HousingComplex created = HousingComplex.createFromMyHome(
                    data.name(),
                    data.sourceComplexIdentifier(),
                    data.supplyType(),
                    address,
                    data.totalHouseholdCount(),
                    data.provider(),
                    data.completionDate(),
                    data.heatingType(),
                    data.housingType(),
                    data.corridorType(),
                    data.elevatorInstalled(),
                    data.parkingSpaceCount()
            );
            created.updateRentalPriceRange(data.rentalPriceRange());
            return new ComplexWriteResult(complexRepository.save(created), 1, 0);
        }
        complex = complexRepository.findByIdForUpdate(complex.getId()).orElseThrow();
        boolean priceUpdated = complex.updateRentalPriceRange(data.rentalPriceRange());
        boolean detailsUpdated = complex.updateFromMyHome(
                data.name(),
                data.supplyType(),
                address,
                data.totalHouseholdCount(),
                data.provider(),
                data.completionDate(),
                data.heatingType(),
                data.housingType(),
                data.corridorType(),
                data.elevatorInstalled(),
                data.parkingSpaceCount()
        );
        if (priceUpdated || detailsUpdated) {
            return new ComplexWriteResult(complex, 0, 1);
        }
        return new ComplexWriteResult(complex, 0, 0);
    }

    private MyHomeComplexMappingReport writeVerifiedGroup(MyHomeComplexLink link) {
        HousingComplex complex = complexRepository.findByIdForUpdate(link.getHousingComplex().getId()).orElseThrow();
        List<MyHomeComplexLink> links = linkRepository.findAllByHousingComplexId(complex.getId());
        List<Long> identifiers = links.stream().map(MyHomeComplexLink::getSourceComplexIdentifier)
                .map(identifier -> Long.valueOf(identifier.substring(0, identifier.indexOf(':')))).toList();
        Map<String, List<MyHomeComplexSource>> grouped = sourceRepository.findAllByHsmpSnIn(identifiers).stream()
                .collect(Collectors.groupingBy(sourceMapper::sourceComplexIdentifier));
        List<MyHomeHousingTypeMappingData> housingTypes = new ArrayList<>();
        List<MyHomeComplexSource> allSources = new ArrayList<>();
        for (MyHomeComplexLink member : links) {
            List<MyHomeComplexSource> sources = grouped.get(member.getSourceComplexIdentifier());
            if (sources == null || sources.isEmpty()) {
                throw mergedSourceConflict("확인된 연결 원천이 누락되어 기존 단지와 주택형을 보존합니다.");
            }
            MyHomeComplexMappingData data = sourceMapper.map(member.getSourceComplexIdentifier(), sources);
            if (!data.matchesVerifiedProduct(complex)
                    || !java.util.Objects.equals(member.getApprovedHouseholdCount(), data.totalHouseholdCount())) {
                throw mergedSourceConflict("통합 근거의 단지 공통값 또는 원천 세대수가 변경되어 재확인이 필요합니다.");
            }
            housingTypes.addAll(data.housingTypes());
            allSources.addAll(sources);
        }
        int priceUpdated = 0;
        if (complex.updateRentalPriceRange(sourceMapper.rentalPriceRange(allSources))) {
            priceUpdated = 1;
        }
        HousingTypeWriteResult result = synchronizeHousingTypes(complex, housingTypes, true);
        return new MyHomeComplexMappingReport(
                0, priceUpdated, 1 - priceUpdated,
                result.created(), result.updated(), result.unchanged(), result.deleted(), 0);
    }

    private MyHomeComplexMappingRejectedException mergedSourceConflict(String message) {
        return new MyHomeComplexMappingRejectedException(
                MyHomeComplexMappingFailureReason.CONFLICTING_SOURCE_VALUE, message);
    }

    private void rejectChangedVerifiedSupplyType(String identifier) {
        String hsmpSn = identifier.substring(0, identifier.indexOf(':'));
        List<MyHomeComplexLink> verifiedLinks = linkRepository
                .findAllBySourceComplexIdentifierStartingWithAndMergeIdIsNotNull(hsmpSn + ":");
        if (verifiedLinks.isEmpty()) {
            return;
        }
        List<String> currentIdentifiers = sourceRepository.findAllByHsmpSnIn(List.of(Long.valueOf(hsmpSn))).stream()
                .map(sourceMapper::sourceComplexIdentifier).distinct().toList();
        boolean missingVerifiedSource = verifiedLinks.stream().map(MyHomeComplexLink::getSourceComplexIdentifier)
                .anyMatch(approved -> !currentIdentifiers.contains(approved));
        if (missingVerifiedSource) {
            throw mergedSourceConflict("확인된 원천의 공급유형이 변경되어 단지 생성과 갱신을 보류합니다.");
        }
    }

    private HousingTypeWriteResult synchronizeHousingTypes(
            HousingComplex complex,
            List<MyHomeHousingTypeMappingData> incoming
    ) {
        return synchronizeHousingTypes(complex, incoming, false);
    }

    private HousingTypeWriteResult synchronizeHousingTypes(
            HousingComplex complex,
            List<MyHomeHousingTypeMappingData> incoming,
            boolean preserveSupplementalValues
    ) {
        Map<String, HousingType> storedByIdentifier = housingTypeRepository.findAllByHousingComplex(complex)
                .stream()
                .filter(type -> type.getSourceHousingTypeIdentifier() != null)
                .collect(Collectors.toMap(
                        HousingType::getSourceHousingTypeIdentifier,
                        Function.identity(),
                        (first, second) -> first,
                        LinkedHashMap::new
                ));
        MatchedHousingTypes matched = updateMatchedHousingTypes(storedByIdentifier, incoming);
        HousingTypeWriteResult unmatched = writeUnmatchedHousingTypes(complex, storedByIdentifier, matched.unmatched());
        int deleted = deleteStaleHousingTypes(storedByIdentifier, preserveSupplementalValues);
        return new HousingTypeWriteResult(
                unmatched.created(), matched.updated() + unmatched.updated(), matched.unchanged(), deleted
        );
    }

    private MatchedHousingTypes updateMatchedHousingTypes(
            Map<String, HousingType> storedByIdentifier,
            List<MyHomeHousingTypeMappingData> incoming
    ) {
        int updated = 0;
        int unchanged = 0;
        List<MyHomeHousingTypeMappingData> unmatchedIncoming = new ArrayList<>();
        for (MyHomeHousingTypeMappingData data : incoming) {
            HousingType housingType = storedByIdentifier.remove(data.sourceHousingTypeIdentifier());
            if (housingType == null) {
                unmatchedIncoming.add(data);
                continue;
            }
            if (housingType.updateFromMyHome(
                    data.sourceHousingTypeIdentifier(),
                    data.name(),
                    data.exclusiveArea(),
                    data.supplyArea()
            )) {
                updated++;
                continue;
            }
            unchanged++;
        }
        return new MatchedHousingTypes(unmatchedIncoming, updated, unchanged);
    }

    private HousingTypeWriteResult writeUnmatchedHousingTypes(
            HousingComplex complex,
            Map<String, HousingType> storedByIdentifier,
            List<MyHomeHousingTypeMappingData> unmatchedIncoming
    ) {
        int created = 0;
        int updated = 0;
        for (MyHomeHousingTypeMappingData data : unmatchedIncoming) {
            HousingType corrected = findUniqueStoredTypeByName(storedByIdentifier, data);
            if (corrected == null) {
                housingTypeRepository.save(HousingType.createFromMyHome(
                        complex,
                        data.sourceHousingTypeIdentifier(),
                        data.name(),
                        data.exclusiveArea(),
                        data.supplyArea()
                ));
                created++;
                continue;
            }
            storedByIdentifier.remove(corrected.getSourceHousingTypeIdentifier());
            corrected.updateFromMyHome(
                    data.sourceHousingTypeIdentifier(),
                    data.name(),
                    data.exclusiveArea(),
                    data.supplyArea()
            );
            updated++;
        }
        return new HousingTypeWriteResult(created, updated, 0, 0);
    }

    private int deleteStaleHousingTypes(
            Map<String, HousingType> storedByIdentifier,
            boolean preserveSupplementalValues
    ) {
        List<HousingType> stale = List.copyOf(storedByIdentifier.values());
        List<HousingType> deletable = stale.stream()
                .filter(type -> !supplyRowRepository.existsByHousingType(type))
                .filter(type -> !preserveSupplementalValues || !type.hasSupplementalInformation())
                .toList();
        housingTypeRepository.deleteAll(deletable);
        return deletable.size();
    }

    private HousingType findUniqueStoredTypeByName(
            Map<String, HousingType> storedByIdentifier,
            MyHomeHousingTypeMappingData incoming
    ) {
        List<HousingType> sameName = storedByIdentifier.values()
                .stream()
                .filter(type -> type.getName().equals(incoming.name()))
                .filter(type -> MyHomeComplexSource.sameSourceComplex(
                        type.getSourceHousingTypeIdentifier(), incoming.sourceHousingTypeIdentifier()))
                .toList();
        if (sameName.size() != 1) {
            return null;
        }
        return sameName.getFirst();
    }

    private record ComplexWriteResult(HousingComplex complex, int created, int updated) {

        int unchanged() {
            return 1 - created - updated;
        }
    }

    private record MatchedHousingTypes(List<MyHomeHousingTypeMappingData> unmatched, int updated, int unchanged) {
    }

    private record HousingTypeWriteResult(int created, int updated, int unchanged, int deleted) {
    }
}
