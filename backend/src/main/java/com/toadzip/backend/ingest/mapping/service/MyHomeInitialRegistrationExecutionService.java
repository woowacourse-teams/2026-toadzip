package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.location.domain.NormalizedRoadAddress;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationStatus;
import com.toadzip.backend.ingest.mapping.dto.InitialRegistrationRunResponse;
import com.toadzip.backend.ingest.mapping.repository.InitialRegistrationTargetRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import com.toadzip.backend.ingest.mapping.domain.InitialRegistrationTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 실행을 조율하며, 실제 쓰기 트랜잭션은 준비 확인과 단지별 저장 서비스가 소유한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyHomeInitialRegistrationExecutionService {

    private final MyHomeInitialRegistrationService preparation;
    private final InitialRegistrationTargetRepository targets;
    private final MyHomeInitialRegistrationWriter writer;

    public InitialRegistrationRunResponse register(String actor) {
        IngestExecutionScope.requireHeld();
        preparation.refresh();
        var ready = targets.findAllByStatusOrderBySourceComplexIdentifier(InitialRegistrationStatus.READY);
        return process(actor, ready, false);
    }

    public InitialRegistrationRunResponse retry(String actor, List<String> identifiers) {
        IngestExecutionScope.requireHeld();
        var selected = targets.findAllById(identifiers).stream()
                .filter(target -> !target.isCompleted() && target.getStatus() != InitialRegistrationStatus.EXISTING)
                .sorted(java.util.Comparator.comparing(InitialRegistrationTarget::getSourceComplexIdentifier)).toList();
        return process(actor, selected, true);
    }

    private InitialRegistrationRunResponse process(String actor, List<InitialRegistrationTarget> ready, boolean retry) {
        Map<InitialRegistrationStatus, Long> counts = new EnumMap<>(InitialRegistrationStatus.class);
        for (var status : InitialRegistrationStatus.values()) {
            counts.put(status, 0L);
        }
        if (!retry) {
            counts.put(InitialRegistrationStatus.EXISTING, targets.countByStatus(InitialRegistrationStatus.EXISTING));
        }
        IngestExecutionScope.beginWork("신규 단지 등록", "곳", ready.size());
        int addresses = (int) ready.stream().map(target -> target.getRoadAddress()).filter(Objects::nonNull)
                .filter(address -> !address.isBlank())
                .map(address -> new NormalizedRoadAddress(address).withoutReference())
                .distinct().count();
        for (var target : ready) {
            IngestExecutionScope.checkStopRequested();
            String identifier = target.getSourceComplexIdentifier();
            IngestExecutionScope.requireHeld();
            try {
                counts.merge(processOne(identifier, actor, retry), 1L, Long::sum);
            }
            catch (RuntimeException exception) {
                IngestExecutionScope.requireHeld();
                log.error("최초 단지 등록 실패: sourceComplexIdentifier={}", identifier, exception);
                writer.recordFailure(identifier, actor);
                counts.merge(InitialRegistrationStatus.FAILED, 1L, Long::sum);
            }
            IngestExecutionScope.workCompleted();
        }
        return new InitialRegistrationRunResponse(counts, ready.size(), addresses);
    }

    private InitialRegistrationStatus processOne(String identifier, String actor, boolean retry) {
        if (retry) {
            return writer.retry(identifier, actor);
        }
        return writer.register(identifier, actor);
    }
}
