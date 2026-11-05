package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceView;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexSourceReader;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationTarget;
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
                var result = assessment.assess(identifier, rows, index);
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
        if (groups.containsKey(identifier) || target.isCompleted()
                || target.getStatus() == InitialRegistrationStatus.EXISTING) {
            return;
        }
        target.assess(target.getName(), target.getRoadAddress(), InitialRegistrationStatus.INFORMATION_REQUIRED,
                "현재 수집 원천에서 찾을 수 없습니다. 원천 누락 또는 등록 대상 제외 여부를 확인하세요.",
                target.getHousingComplexId(), clock.instant());
    }
}
