package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexSourceReader;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexLink;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMerge;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexLinkRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMergeStore;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexSourceMapper.MyHomeComplexMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexSourceMapper.MyHomeHousingTypeMappingData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MyHomeComplexMappingWriter {

    private final HousingComplexRepository complexRepository;

    private final MyHomeComplexLinkRepository linkRepository;
    private final MyHomeComplexSourceReader sourceRepository;
    private final MyHomeComplexSourceMapper sourceMapper;
    private final MyHomeComplexMergeStore mergeStore;
    private final SavedSupplyMatches savedMatches;
    private final AnnouncementSupplySplitWriter splitWriter;
    private final MyHomeHousingTypeSynchronizer housingTypes;

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
        var housingTypeResult = housingTypes.synchronize(complexResult.complex(), data.housingTypes(), false);
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
        var housingTypeResult = housingTypes.synchronize(
                complex, housingTypes.distinct(complex, combined.housingTypes()), false);
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
        MyHomeComplexMerge.ManualValues manualValues;
        try {
            manualValues = MyHomeComplexMerge.manualValues(complexes);
        }
        catch (IllegalArgumentException exception) {
            throw mergedSourceConflict("분할 단지의 수동 입력값이 서로 달라 통합을 보류합니다.");
        }
        if (mergeStore.countHouseholdTargetDecisions(complexIds) > 1) {
            throw mergedSourceConflict("분할 단지마다 LH 세대수 결정이 있어 통합을 보류합니다. 한쪽 결정을 해제해야 합니다.");
        }
        HousingComplex representative = complexes.getFirst();
        List<Long> donorIds = complexIds.subList(1, complexIds.size());
        List<Long> validDecisions = savedMatches.validIn(donorIds);
        List<Long> validGroupRows = splitWriter.confirmedRowsIn(donorIds);
        manualValues.adoptInto(representative, representative.getTotalHouseholdCount());
        mergeStore.absorbSplitComplexes(representative.getId(), donorIds);
        savedMatches.moveToComplex(validDecisions, representative.getId());
        splitWriter.reconfirmAfterComplexMove(validGroupRows);
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
        Map<String, List<MyHomeComplexSourceView>> grouped = sourceRepository.findAllByHsmpSnIn(identifiers).stream()
                .collect(Collectors.groupingBy(sourceMapper::sourceComplexIdentifier));
        List<MyHomeHousingTypeMappingData> housingTypes = new ArrayList<>();
        for (MyHomeComplexLink member : links) {
            List<MyHomeComplexSourceView> sources = grouped.get(member.getSourceComplexIdentifier());
            if (sources == null || sources.isEmpty()) {
                throw mergedSourceConflict("확인된 연결 원천이 누락되어 기존 단지와 주택형을 보존합니다.");
            }
            MyHomeComplexMappingData data = sourceMapper.map(member.getSourceComplexIdentifier(), sources);
            if (!data.matchesVerifiedProduct(complex)
                    || !Objects.equals(member.getApprovedHouseholdCount(), data.totalHouseholdCount())) {
                throw mergedSourceConflict("통합 근거의 단지 공통값 또는 원천 세대수가 변경되어 재확인이 필요합니다.");
            }
            housingTypes.addAll(data.housingTypes());
        }
        var result = this.housingTypes.synchronize(complex, housingTypes, true);
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

    private record ComplexWriteResult(HousingComplex complex, int created, int updated) {

        int unchanged() {
            return 1 - created - updated;
        }
    }

}
