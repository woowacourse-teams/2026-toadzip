package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSource;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexSourceReader;
import com.toadzip.backend.ingest.domain.SupplyNameNormalizer;
import com.toadzip.backend.ingest.mapping.domain.AnnouncementSupplyMatchDecision;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexLink;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMerge;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.mapping.repository.AnnouncementSupplyMatchDecisionRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexLinkRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMergeStore;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexSourceMapper.MyHomeComplexMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexSourceMapper.MyHomeComplexMappingRejectedException;
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
    private final MyHomeComplexSourceReader sourceRepository;
    private final MyHomeComplexSourceMapper sourceMapper;
    private final MyHomeComplexMergeStore mergeStore;
    private final AnnouncementSupplyMatchDecisionRepository matchDecisionRepository;
    private final SupplyMatchingFingerprint fingerprints;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MyHomeComplexMappingReport write(MyHomeComplexMappingData data, Address address) {
        MyHomeComplexLink link = linkRepository.findById(data.sourceComplexIdentifier()).orElse(null);
        if (link != null && link.getMergeId() != null) {
            throw mergedSourceConflict("확인된 원천 연결이 변경되어 재정제를 보류합니다.");
        }
        rejectChangedVerifiedSupplyType(data.sourceComplexIdentifier());
        ComplexWriteResult complexResult = upsertComplex(data, address, link);
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
    public MyHomeComplexMappingReport writeSplit(
            List<MyHomeComplexMappingData> members, MyHomeComplexMappingData combined, Address address
    ) {
        List<String> identifiers = members.stream().map(MyHomeComplexMappingData::sourceComplexIdentifier).toList();
        List<MyHomeComplexLink> links = linkRepository.findAllById(identifiers);
        if (links.stream().anyMatch(link -> link.getMergeId() != null)) {
            throw mergedSourceConflict("확인된 원천 연결이 변경되어 재정제를 보류합니다.");
        }
        identifiers.forEach(this::rejectChangedVerifiedSupplyType);
        List<Long> complexIds = links.stream().map(link -> link.getHousingComplex().getId())
                .distinct().sorted().toList();
        ComplexWriteResult complexResult;
        if (complexIds.isEmpty()) {
            complexResult = upsertComplex(combined, address, null);
        }
        else {
            absorbSplitComplexes(complexIds);
            HousingComplex representative = complexRepository.findById(complexIds.getFirst()).orElseThrow();
            complexResult = updateComplex(representative, combined, address);
        }
        HousingComplex complex = complexResult.complex();
        List<String> linked = links.stream().map(MyHomeComplexLink::getSourceComplexIdentifier).toList();
        identifiers.stream().filter(identifier -> !linked.contains(identifier))
                .forEach(identifier -> linkRepository.save(MyHomeComplexLink.connect(identifier, complex)));
        HousingTypeWriteResult housingTypeResult = synchronizeHousingTypes(
                complex, distinctHousingTypes(complex, combined.housingTypes()));
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

    private void absorbSplitComplexes(List<Long> complexIds) {
        if (complexIds.size() == 1) {
            return;
        }
        List<HousingComplex> complexes = complexIds.stream()
                .map(id -> complexRepository.findByIdForUpdate(id).orElseThrow()).toList();
        if (complexes.stream().anyMatch(complex -> complex.isAdminModified() || complex.isAdminDeleted())) {
            throw mergedSourceConflict("관리자가 수정했거나 삭제한 단지가 있어 분할 단지 통합을 보류합니다.");
        }
        String imageUrl;
        Integer moveOutCount;
        try {
            imageUrl = MyHomeComplexMerge.singleManualValue(complexes, HousingComplex::getImageUrl);
            moveOutCount = MyHomeComplexMerge.singleManualValue(
                    complexes, HousingComplex::getRecentOneYearMoveOutCount);
        }
        catch (IllegalArgumentException exception) {
            throw mergedSourceConflict("분할 단지의 수동 입력값이 서로 달라 통합을 보류합니다.");
        }
        if (mergeStore.countHouseholdTargetDecisions(complexIds) > 1) {
            throw mergedSourceConflict("분할 단지마다 LH 세대수 결정이 있어 통합을 보류합니다. 한쪽 결정을 해제해야 합니다.");
        }
        HousingComplex representative = complexes.getFirst();
        List<Long> donorIds = complexIds.subList(1, complexIds.size());
        List<Long> validDecisions = matchDecisionRepository.findAllByComplexIdIn(donorIds).stream()
                .filter(this::hasValidTarget).map(AnnouncementSupplyMatchDecision::getId).toList();
        List<Long> validGroupRows = donorIds.stream().flatMap(id -> supplyRowRepository.findAllByHousingComplexId(id)
                        .stream())
                .filter(row -> row.getConfirmedTargetFingerprint() != null && row.getHousingType() != null
                        && fingerprints.target(row.getHousingType()).equals(row.getConfirmedTargetFingerprint()))
                .map(SupplyRow::getId).toList();
        representative.adoptVerifiedMerge(representative.getTotalHouseholdCount(), imageUrl, moveOutCount);
        mergeStore.absorbSplitComplexes(representative.getId(), donorIds);
        matchDecisionRepository.findAllById(validDecisions).forEach(decision -> decision.moveToComplex(
                representative.getId(),
                fingerprints.target(housingTypeRepository.findById(decision.getHousingTypeId()).orElseThrow())));
        supplyRowRepository.findAllById(validGroupRows).forEach(row ->
                row.reconfirmTargetAfterComplexMove(fingerprints.target(row.getHousingType())));
    }

    private boolean hasValidTarget(AnnouncementSupplyMatchDecision decision) {
        HousingType type = decision.getHousingTypeId() == null ? null
                : housingTypeRepository.findById(decision.getHousingTypeId()).orElse(null);
        return type != null && type.getHousingComplex().getId().equals(decision.getComplexId())
                && fingerprints.target(type).equals(decision.getTargetFingerprint());
    }

    private ComplexWriteResult upsertComplex(
            MyHomeComplexMappingData data, Address address, MyHomeComplexLink link
    ) {
        HousingComplex complex = link != null ? link.getHousingComplex() : complexRepository
                .findBySourceComplexIdentifier(data.sourceComplexIdentifier())
                .orElse(null);
        if (complex == null) {
            HousingComplex adopted = adminRegisteredComplex(data, address);
            if (adopted != null) {
                return fillAdminRegisteredComplex(adopted, data);
            }
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
            return new ComplexWriteResult(complexRepository.save(created), 1, 0);
        }
        return updateComplex(complex, data, address);
    }

    /**
     * 마이홈에 없던 입주 전 단지는 관리자가 먼저 등록한다. 나중에 마이홈 단지가 생기면 같은 PNU·공급유형의 관리자 등록 단지가
     * 하나뿐이고 아직 다른 마이홈 원천에 연결되지 않았을 때 새로 만들지 않고 그 단지에 연결한다.
     */
    private HousingComplex adminRegisteredComplex(MyHomeComplexMappingData data, Address address) {
        List<HousingComplex> candidates = complexRepository.findAllByPnuAndSupplyType(address.getPnu(), data.supplyType())
                .stream().filter(HousingComplex::isAdminRegistered)
                .filter(candidate -> linkRepository.findAllByHousingComplexId(candidate.getId()).isEmpty()).toList();
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    // 관리자 등록 단지는 관리자가 정한 단지명·주소·세대수를 유지하고, 비워 둔 값만 마이홈 값으로 채운다.
    private ComplexWriteResult fillAdminRegisteredComplex(HousingComplex complex, MyHomeComplexMappingData data) {
        complex = complexRepository.findByIdForUpdate(complex.getId()).orElseThrow();
        boolean filled = complex.fillBlanksFromMyHome(data.completionDate(), data.heatingType(), data.housingType(),
                data.corridorType(), data.elevatorInstalled(), data.parkingSpaceCount());
        return new ComplexWriteResult(complex, 0, filled ? 1 : 0);
    }

    private ComplexWriteResult updateComplex(HousingComplex complex, MyHomeComplexMappingData data, Address address) {
        if (complex.isAdminRegistered()) {
            return fillAdminRegisteredComplex(complex, data);
        }
        complex = complexRepository.findByIdForUpdate(complex.getId()).orElseThrow();
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
        if (detailsUpdated) {
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
        }
        HousingTypeWriteResult result = synchronizeHousingTypes(complex, housingTypes, true);
        return new MyHomeComplexMappingReport(
                0, 0, 1,
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

    private List<MyHomeHousingTypeMappingData> distinctHousingTypes(
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
        HousingTypeWriteResult unmatched = writeUnmatchedHousingTypes(
                complex, remainingStoredByIdentifier, matched.unmatched()
        );
        int deleted = deleteStaleHousingTypes(remainingStoredByIdentifier, preserveSupplementalValues);
        return new HousingTypeWriteResult(
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

    private HousingTypeWriteResult writeUnmatchedHousingTypes(
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
        return new HousingTypeWriteResult(created, updated, 0, 0);
    }

    private int deleteStaleHousingTypes(
            Map<String, HousingType> remainingStoredByIdentifier,
            boolean preserveSupplementalValues
    ) {
        List<HousingType> stale = List.copyOf(remainingStoredByIdentifier.values());
        List<HousingType> deletable = stale.stream()
                .filter(type -> !type.isAdminModified())
                .filter(type -> !supplyRowRepository.existsByHousingType(type))
                .filter(type -> !matchDecisionRepository.existsByHousingTypeId(type.getId()))
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
