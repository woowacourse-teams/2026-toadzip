package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexSourceReader;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.location.domain.RoadAddressGeocodingFailureReason;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationLocation;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationTarget;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationLocationRequest;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationLocationsResponse;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationResponse;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationProductStore;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationTargetRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import com.toadzip.backend.ingest.pipeline.service.IngestWriteOwnershipGuard;
import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MyHomeInitialRegistrationService {

    private final MyHomeComplexSourceReader sources;
    private final MyHomeComplexSourceMapper mapper;
    private final InitialRegistrationTargetRepository targets;
    private final InitialRegistrationProductStore products;
    private final MyHomeInitialRegistrationAssessment assessment;
    private final IngestWriteOwnershipGuard ownership;
    private final Clock clock;

    @Transactional
    public void refresh() {
        IngestExecutionScope.requireHeld();
        InitialRegistrationProductIndex index = new InitialRegistrationProductIndex(
                products.findProducts(), products.findLinks());
        Map<String, InitialRegistrationTarget> saved = targets.findAll().stream().collect(Collectors.toMap(
                InitialRegistrationTarget::getSourceComplexIdentifier, Function.identity()));
        Map<String, List<MyHomeComplexSourceView>> groups = sources.findAll().stream()
                .filter(source -> !mapper.shouldSkip(source))
                .collect(Collectors.groupingBy(this::identifier, LinkedHashMap::new, Collectors.toList()));
        groups.forEach((identifier, rows) -> {
            InitialRegistrationTarget target = saved.computeIfAbsent(identifier, InitialRegistrationTarget::create);
            if (!target.isCompleted()) {
                var result = assessment.assess(target, rows, index, clock.instant());
                var source = rows.getFirst();
                target.assess(source.getHsmpNm(), source.getRnAdres(), result.status(), result.detail(),
                        result.complexId(), clock.instant());
            }
        });
        saved.forEach((identifier, target) -> markMissing(identifier, target, groups));
        ownership.verifyWrite();
        targets.saveAll(saved.values());
    }

    @Transactional(readOnly = true)
    public InitialRegistrationResponse find(InitialRegistrationStatus status, int page, int size) {
        var result = targets.findTargets(status, PageRequest.of(page, size));
        Map<InitialRegistrationStatus, Long> counts = new EnumMap<>(InitialRegistrationStatus.class);
        for (InitialRegistrationStatus value : InitialRegistrationStatus.values()) {
            counts.put(value, 0L);
        }
        targets.countByStatus().forEach(count -> counts.put(count.getStatus(), count.getTotal()));
        return new InitialRegistrationResponse(counts, result.stream().map(InitialRegistrationResponse.Target::from)
                .toList(), result.getTotalElements(), page, result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public InitialRegistrationLocationsResponse findLocations(int page, int size) {
        var result = targets.findLocationFailures(PageRequest.of(page, size));
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("READY", 0L);
        for (var reason : RoadAddressGeocodingFailureReason.values()) {
            counts.put(reason.name(), 0L);
        }
        targets.countLocations().forEach(count -> counts.put(count.getReason(), count.getTotal()));
        return new InitialRegistrationLocationsResponse(counts,
                result.stream().map(InitialRegistrationResponse.Target::from).toList(),
                result.getTotalElements(), page, result.getTotalPages());
    }

    @Transactional
    public void supplementLocation(InitialRegistrationLocationRequest request, String actor) {
        IngestExecutionScope.requireHeld();
        refresh();
        var target = targets.findById(request.sourceComplexIdentifier()).orElseThrow(
                () -> new InvalidIngestRequestException("주소·좌표를 보완할 등록 대상이 없습니다."));
        if (target.isCompleted() || target.getStatus() == InitialRegistrationStatus.EXISTING
                || target.getHousingComplexId() != null) {
            throw new InvalidIngestRequestException("기존 단지와 등록 완료 대상은 주소·좌표를 보완할 수 없습니다.");
        }
        boolean sourcePresent = sources.findAll().stream()
                .filter(source -> !mapper.shouldSkip(source))
                .anyMatch(source -> identifier(source).equals(request.sourceComplexIdentifier()));
        if (!sourcePresent) {
            throw new InvalidIngestRequestException("현재 원천에 없는 대상입니다. 원천을 먼저 확인하세요.");
        }
        target.prepareLocation(InitialRegistrationLocation.prepared(target.getRoadAddress(), request.address(),
                request.latitude(), request.longitude(), true, actor, clock.instant()));
        ownership.verifyWrite();
        targets.saveAndFlush(target);
        refresh();
    }

    private String identifier(MyHomeComplexSourceView source) {
        try {
            return mapper.sourceComplexIdentifier(source);
        }
        catch (MyHomeComplexMappingRejectedException exception) {
            return source.getHsmpSn() + ":UNKNOWN";
        }
    }

    private void markMissing(String identifier, InitialRegistrationTarget target,
            Map<String, List<MyHomeComplexSourceView>> groups) {
        if (groups.containsKey(identifier)) {
            return;
        }
        target.markSourceMissing(clock.instant());
    }
}
