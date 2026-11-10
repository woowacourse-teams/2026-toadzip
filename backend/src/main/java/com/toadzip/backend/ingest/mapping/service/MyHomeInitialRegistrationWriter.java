package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexSourceReader;
import com.toadzip.backend.ingest.location.domain.GeocodedRoadAddress;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationTarget;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexLink;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationProductStore;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationTargetRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexLinkRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 신규 단지 하나의 제품·주택형·원천 연결·이력을 같은 트랜잭션으로 저장한다. */
@Service
@RequiredArgsConstructor
public class MyHomeInitialRegistrationWriter {

    private final InitialRegistrationTargetRepository targets;
    private final MyHomeComplexSourceReader sources;
    private final MyHomeComplexSourceMapper mapper;
    private final MyHomeInitialRegistrationAssessment assessment;
    private final InitialRegistrationProductStore products;
    private final HousingComplexRepository complexes;
    private final HousingTypeRepository types;
    private final MyHomeComplexLinkRepository links;
    private final AdminDataChangeRepository changes;
    private final IngestWriteOwnershipGuard ownership;
    private final ObjectMapper json;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InitialRegistrationStatus register(String identifier, String actor) {
        IngestExecutionScope.requireHeld();
        ownership.verifyWrite();
        var target = targets.findForUpdate(identifier).orElseThrow();
        if (target.getStatus() != InitialRegistrationStatus.READY) {
            return target.getStatus();
        }
        long sourceId = Long.parseLong(identifier.substring(0, identifier.indexOf(':')));
        var rows = sources.findAllByHsmpSnIn(List.of(sourceId)).stream()
                .filter(row -> !mapper.shouldSkip(row))
                .filter(row -> matches(identifier, row)).toList();
        if (rows.isEmpty()) {
            target.markSourceMissing(clock.instant());
            return target.getStatus();
        }
        List<String> addresses = new ArrayList<>(rows.stream().map(MyHomeComplexSourceView::getRnAdres).toList());
        if (target.getLocation() != null) {
            addresses.add(target.getLocation().getAddress());
        }
        var index = new InitialRegistrationProductIndex(products.findRegistrationCandidates(identifier,
                rows.stream().map(MyHomeComplexSourceView::getPnu).toList(),
                rows.stream().map(MyHomeComplexSourceView::getHsmpNm).toList(), addresses),
                products.findLinks(identifier));
        var result = assessment.assess(target, rows, index, clock.instant());
        var source = rows.getFirst();
        target.assess(source.getHsmpNm(), source.getRnAdres(), result.status(), result.detail(),
                result.complexId(), clock.instant());
        if (target.getStatus() != InitialRegistrationStatus.READY) {
            return target.getStatus();
        }
        var location = target.getLocation();
        var data = mapper.map(identifier, rows, location.getAddress());
        var address = data.address().resolve(new GeocodedRoadAddress(location.getAddress(),
                location.getLatitude(), location.getLongitude()));
        var complex = complexes.save(HousingComplex.createFromMyHome(data.name(), identifier, data.supplyType(),
                address, data.totalHouseholdCount(), data.provider(), data.completionDate(), data.heatingType(),
                data.housingType(), data.corridorType(), data.elevatorInstalled(), data.parkingSpaceCount()));
        for (var type : data.housingTypes()) {
            var created = HousingType.createFromMyHome(complex, type.sourceHousingTypeIdentifier(), type.name(),
                    type.exclusiveArea(), type.supplyArea());
            created.updateBasicRentalCondition(type.basicRentalCondition());
            types.save(created);
        }
        links.save(MyHomeComplexLink.connect(identifier, complex));
        Map<String, Object> applied = new LinkedHashMap<>();
        applied.put("sourceComplexIdentifier", identifier);
        applied.put("sourceValues", rows.stream().map(row -> row.snapshot()).toList());
        applied.put("appliedValues", data);
        applied.put("appliedAddress", address);
        applied.put("location", location);
        changes.save(new AdminDataChange("COMPLEX", complex.getId(), "INITIAL_REGISTER", actor, "{}",
                json.writeValueAsString(applied)));
        target.complete(complex.getId(), clock.instant());
        targets.flush();
        return target.getStatus();
    }

    private boolean matches(String identifier, MyHomeComplexSourceView row) {
        try {
            return identifier.equals(mapper.sourceComplexIdentifier(row));
        }
        catch (MyHomeComplexMappingRejectedException exception) {
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String identifier) {
        IngestExecutionScope.requireHeld();
        ownership.verifyWrite();
        InitialRegistrationTarget target = targets.findForUpdate(identifier).orElseThrow();
        if (target.getStatus() == InitialRegistrationStatus.READY) {
            target.fail("단지·주택형 저장에 실패했습니다. 실행 로그를 확인하세요.", clock.instant());
        }
    }
}
