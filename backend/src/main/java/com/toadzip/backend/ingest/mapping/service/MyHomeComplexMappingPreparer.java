package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.ingest.collection.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.collection.repository.MyHomeComplexSourceRepository;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingCandidate;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailure;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingPreparationReport;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingCandidateRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingCandidateStore;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingFailureStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
class MyHomeComplexMappingPreparer {

    private final MyHomeComplexSourceRepository sourceRepository;
    private final MyHomeComplexMappingFailureStore failureStore;
    private final MyHomeComplexMappingCandidateRepository candidateRepository;
    private final MyHomeComplexMappingCandidateStore candidateStore;
    private final MyHomeComplexSourceMapper sourceMapper;
    private final Clock clock;

    MyHomeComplexMappingPreparer(
            MyHomeComplexSourceRepository sourceRepository,
            MyHomeComplexMappingFailureStore failureStore,
            MyHomeComplexMappingCandidateRepository candidateRepository,
            MyHomeComplexMappingCandidateStore candidateStore,
            MyHomeComplexSourceMapper sourceMapper,
            Clock clock
    ) {
        this.sourceRepository = sourceRepository;
        this.failureStore = failureStore;
        this.candidateRepository = candidateRepository;
        this.candidateStore = candidateStore;
        this.sourceMapper = sourceMapper;
        this.clock = clock;
    }

    MyHomeComplexMappingPreparationReport prepare() {
        Instant occurredAt = clock.instant();
        List<MyHomeComplexMappingFailure> failures = new ArrayList<>();
        Map<String, List<MyHomeComplexSource>> groupedSources = groupSources(
                sourceRepository.findAll(), failures, occurredAt
        );
        Map<String, MyHomeComplexMappingCandidate> storedCandidates = candidateRepository.findAll()
                .stream()
                .collect(Collectors.toMap(
                        MyHomeComplexMappingCandidate::getSourceComplexIdentifier,
                        Function.identity()
                ));
        List<MyHomeComplexMappingCandidate> preparedCandidates = new ArrayList<>();
        for (Map.Entry<String, List<MyHomeComplexSource>> entry : groupedSources.entrySet()) {
            prepareCandidate(
                    entry.getKey(), entry.getValue(), storedCandidates,
                    preparedCandidates, failures, occurredAt
            );
        }
        synchronizeCandidates(storedCandidates, preparedCandidates);
        failureStore.replacePreparationFailures(failures);
        return new MyHomeComplexMappingPreparationReport(preparedCandidates.size(), failures.size());
    }

    Map<String, List<MyHomeComplexSource>> sourcesFor(
            Collection<MyHomeComplexMappingCandidate> candidates
    ) {
        List<Long> sourceIdentifiers = candidates.stream()
                .map(MyHomeComplexMappingCandidate::getSourceComplexIdentifier)
                .map(identifier -> identifier.substring(0, identifier.indexOf(':')))
                .map(Long::valueOf)
                .toList();
        return groupSources(
                sourceRepository.findAllByHsmpSnIn(sourceIdentifiers),
                new ArrayList<>(),
                clock.instant()
        );
    }

    private void prepareCandidate(
            String sourceComplexIdentifier,
            List<MyHomeComplexSource> sources,
            Map<String, MyHomeComplexMappingCandidate> storedCandidates,
            List<MyHomeComplexMappingCandidate> preparedCandidates,
            List<MyHomeComplexMappingFailure> failures,
            Instant occurredAt
    ) {
        try {
            MyHomeComplexMappingData data = sourceMapper.map(sourceComplexIdentifier, sources);
            MyHomeComplexMappingCandidate candidate = storedCandidates.get(sourceComplexIdentifier);
            if (candidate == null) {
                candidate = MyHomeComplexMappingCandidate.pending(
                        sourceComplexIdentifier,
                        data.address().sourceRoadAddress()
                );
            }
            candidate.prepare(data.address().sourceRoadAddress());
            preparedCandidates.add(candidate);
        }
        catch (MyHomeComplexMappingRejectedException exception) {
            addFailures(
                    failures, sources, sourceComplexIdentifier,
                    exception.reason(), exception.getMessage(), occurredAt
            );
        }
    }

    private void synchronizeCandidates(
            Map<String, MyHomeComplexMappingCandidate> storedCandidates,
            List<MyHomeComplexMappingCandidate> preparedCandidates
    ) {
        Set<String> preparedIdentifiers = preparedCandidates.stream()
                .map(MyHomeComplexMappingCandidate::getSourceComplexIdentifier)
                .collect(Collectors.toSet());
        List<MyHomeComplexMappingCandidate> staleCandidates = storedCandidates.values()
                .stream()
                .filter(candidate -> !preparedIdentifiers.contains(candidate.getSourceComplexIdentifier()))
                .toList();
        candidateStore.synchronize(preparedCandidates, staleCandidates);
    }

    private Map<String, List<MyHomeComplexSource>> groupSources(
            Collection<MyHomeComplexSource> sources,
            List<MyHomeComplexMappingFailure> failures,
            Instant occurredAt
    ) {
        Map<String, List<MyHomeComplexSource>> grouped = new LinkedHashMap<>();
        for (MyHomeComplexSource source : sources) {
            groupSource(source, grouped, failures, occurredAt);
        }
        return grouped;
    }

    private void groupSource(
            MyHomeComplexSource source,
            Map<String, List<MyHomeComplexSource>> grouped,
            List<MyHomeComplexMappingFailure> failures,
            Instant occurredAt
    ) {
        if (sourceMapper.shouldSkip(source)) {
            return;
        }
        if (source.getHsmpSn() == null) {
            failures.add(failureOf(
                    source, null, MyHomeComplexMappingFailureReason.MISSING_REQUIRED_VALUE,
                    "단지 식별자 값이 없습니다.", occurredAt
            ));
            return;
        }
        try {
            String identifier = sourceMapper.sourceComplexIdentifier(source);
            grouped.computeIfAbsent(identifier, ignored -> new ArrayList<>()).add(source);
        }
        catch (MyHomeComplexMappingRejectedException exception) {
            failures.add(failureOf(
                    source, String.valueOf(source.getHsmpSn()),
                    exception.reason(), exception.getMessage(), occurredAt
            ));
        }
    }

    private void addFailures(
            List<MyHomeComplexMappingFailure> failures,
            List<MyHomeComplexSource> sources,
            String sourceComplexIdentifier,
            MyHomeComplexMappingFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        for (MyHomeComplexSource source : sources) {
            failures.add(failureOf(source, sourceComplexIdentifier, reason, detail, occurredAt));
        }
    }

    private MyHomeComplexMappingFailure failureOf(
            MyHomeComplexSource source,
            String sourceComplexIdentifier,
            MyHomeComplexMappingFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        return MyHomeComplexMappingFailure.create(
                source.getSourceKey(), sourceComplexIdentifier, reason, detail, occurredAt
        );
    }
}
