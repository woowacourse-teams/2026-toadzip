package com.toadzip.backend.ingest.service;

import static com.toadzip.backend.ingest.domain.MyHomeComplexMappingCandidateStatus.GEOCODED;
import static com.toadzip.backend.ingest.domain.MyHomeComplexMappingCandidateStatus.PENDING;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.ingest.domain.MyHomeComplexMappingCandidate;
import com.toadzip.backend.ingest.domain.MyHomeComplexMappingCandidateStatus;
import com.toadzip.backend.ingest.domain.MyHomeComplexMappingFailure;
import com.toadzip.backend.ingest.domain.MyHomeComplexMappingFailureReason;
import com.toadzip.backend.ingest.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.dto.GeocodedRoadAddress;
import com.toadzip.backend.ingest.dto.MyHomeComplexMappingFailureResponse;
import com.toadzip.backend.ingest.dto.MyHomeComplexMappingPreparationReport;
import com.toadzip.backend.ingest.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason;
import com.toadzip.backend.ingest.repository.MyHomeComplexMappingCandidateRepository;
import com.toadzip.backend.ingest.repository.MyHomeComplexMappingCandidateStore;
import com.toadzip.backend.ingest.repository.MyHomeComplexMappingExecutionLock;
import com.toadzip.backend.ingest.repository.MyHomeComplexMappingFailureRepository;
import com.toadzip.backend.ingest.repository.MyHomeComplexMappingFailureStore;
import com.toadzip.backend.ingest.repository.MyHomeComplexSourceRepository;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class MyHomeComplexMappingService {

    private static final int MAX_BATCH_SIZE = 1_000;

    private static final String PERSISTENCE_FAILURE_DETAIL = "단지와 주택형 저장 중 오류가 발생했습니다.";

    private static final Set<MyHomeComplexMappingCandidateStatus> PROCESSABLE_STATUSES = Set.of(
            PENDING,
            GEOCODED
    );

    private final MyHomeComplexSourceRepository sourceRepository;

    private final MyHomeComplexMappingFailureRepository failureRepository;

    private final MyHomeComplexMappingFailureStore failureStore;

    private final MyHomeComplexMappingCandidateRepository candidateRepository;

    private final MyHomeComplexMappingCandidateStore candidateStore;

    private final MyHomeComplexMappingExecutionLock executionLock;

    private final MyHomeComplexSourceMapper sourceMapper;

    private final RoadAddressGeocodingService geocodingService;

    private final MyHomeComplexMappingWriter writer;

    private final Clock clock;

    public MyHomeComplexMappingService(
            MyHomeComplexSourceRepository sourceRepository,
            MyHomeComplexMappingFailureRepository failureRepository,
            MyHomeComplexMappingFailureStore failureStore,
            MyHomeComplexMappingCandidateRepository candidateRepository,
            MyHomeComplexMappingCandidateStore candidateStore,
            MyHomeComplexMappingExecutionLock executionLock,
            MyHomeComplexSourceMapper sourceMapper,
            RoadAddressGeocodingService geocodingService,
            MyHomeComplexMappingWriter writer,
            Clock clock
    ) {
        this.sourceRepository = sourceRepository;
        this.failureRepository = failureRepository;
        this.failureStore = failureStore;
        this.candidateRepository = candidateRepository;
        this.candidateStore = candidateStore;
        this.executionLock = executionLock;
        this.sourceMapper = sourceMapper;
        this.geocodingService = geocodingService;
        this.writer = writer;
        this.clock = clock;
    }

    public MyHomeComplexMappingPreparationReport prepare() {
        return executionLock.tryRun(this::prepareUnlocked)
                .orElseThrow(this::alreadyRunning);
    }

    private MyHomeComplexMappingPreparationReport prepareUnlocked() {
        Instant occurredAt = clock.instant();
        List<MyHomeComplexMappingFailure> failures = new ArrayList<>();
        Map<String, List<MyHomeComplexSource>> groupedSources = groupSources(
                sourceRepository.findAll(),
                failures,
                occurredAt
        );
        Map<String, MyHomeComplexMappingCandidate> storedCandidates = candidateRepository.findAll()
                .stream()
                .collect(Collectors.toMap(
                        MyHomeComplexMappingCandidate::getSourceComplexIdentifier,
                        Function.identity()
                ));
        List<MyHomeComplexMappingCandidate> preparedCandidates = new ArrayList<>();
        for (Map.Entry<String, List<MyHomeComplexSource>> entry : groupedSources.entrySet()) {
            prepareCandidate(entry.getKey(), entry.getValue(), storedCandidates, preparedCandidates, failures, occurredAt);
        }
        Set<String> preparedCandidateIdentifiers = preparedCandidates.stream()
                .map(MyHomeComplexMappingCandidate::getSourceComplexIdentifier)
                .collect(Collectors.toSet());
        List<MyHomeComplexMappingCandidate> staleCandidates = storedCandidates.values()
                .stream()
                .filter(candidate -> !preparedCandidateIdentifiers.contains(candidate.getSourceComplexIdentifier()))
                .toList();
        candidateStore.synchronize(preparedCandidates, staleCandidates);
        failureStore.replaceAll(failures);
        return new MyHomeComplexMappingPreparationReport(preparedCandidates.size(), failures.size());
    }

    public MyHomeComplexMappingReport mapNext(int batchSize) {
        validateBatchSize(batchSize);
        return executionLock.tryRun(() -> mapNextUnlocked(batchSize))
                .orElseThrow(this::alreadyRunning);
    }

    private MyHomeComplexMappingReport mapNextUnlocked(int batchSize) {
        List<MyHomeComplexMappingCandidate> candidates = candidateRepository.findAllByStatusInOrderByIdAsc(
                PROCESSABLE_STATUSES,
                PageRequest.of(0, batchSize)
        );
        Map<String, List<MyHomeComplexSource>> groupedSources = sourcesFor(candidates);
        MyHomeComplexMappingReport report = MyHomeComplexMappingReport.failedRows(0);
        for (MyHomeComplexMappingCandidate candidate : candidates) {
            report = report.plus(mapCandidate(candidate, groupedSources.get(candidate.getSourceComplexIdentifier())));
        }
        return report;
    }

    public MyHomeComplexMappingReport mapAll() {
        return executionLock.tryRun(this::mapAllUnlocked)
                .orElseThrow(this::alreadyRunning);
    }

    private MyHomeComplexMappingReport mapAllUnlocked() {
        MyHomeComplexMappingPreparationReport preparation = prepareUnlocked();
        MyHomeComplexMappingReport report = MyHomeComplexMappingReport.failedRows(
                preparation.failedSourceRowCount()
        );
        while (hasProcessableCandidate()) {
            report = report.plus(mapNextUnlocked(MAX_BATCH_SIZE));
        }
        return report;
    }

    @Transactional(readOnly = true)
    public List<MyHomeComplexMappingFailureResponse> findFailures() {
        return failureRepository.findAllByOrderBySourceKeyAsc()
                .stream()
                .map(MyHomeComplexMappingFailureResponse::from)
                .toList();
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
                    failures,
                    sources,
                    sourceComplexIdentifier,
                    exception.reason(),
                    exception.getMessage(),
                    occurredAt
            );
        }
    }

    private MyHomeComplexMappingReport mapCandidate(
            MyHomeComplexMappingCandidate candidate,
            List<MyHomeComplexSource> sources
    ) {
        if (sources == null || sources.isEmpty()) {
            candidateStore.delete(candidate);
            return MyHomeComplexMappingReport.failedRows(0);
        }
        Instant occurredAt = clock.instant();
        try {
            MyHomeComplexMappingData data = sourceMapper.map(candidate.getSourceComplexIdentifier(), sources);
            resolveCoordinates(candidate);
            Address address = data.address().resolve(candidate.geocodedAddress());
            MyHomeComplexMappingReport report = writer.write(data, address);
            candidate.markMapped();
            candidateStore.save(candidate);
            failureStore.replaceForComplex(candidate.getSourceComplexIdentifier(), List.of());
            return report;
        }
        catch (MyHomeComplexMappingRejectedException exception) {
            candidateStore.delete(candidate);
            return recordFailure(
                    candidate.getSourceComplexIdentifier(),
                    sources,
                    exception.reason(),
                    exception.getMessage(),
                    occurredAt
            );
        }
        catch (RoadAddressGeocodingException exception) {
            candidate.failGeocoding(exception.getReason());
            candidateStore.save(candidate);
            MyHomeComplexMappingReport failureReport = recordFailure(
                    candidate.getSourceComplexIdentifier(),
                    sources,
                    MyHomeComplexMappingFailureReason.GEOCODING_ERROR,
                    "도로명주소 좌표 변환 실패: " + exception.getReason() + ", " + exception.getMessage(),
                    occurredAt
            );
            if (exception.getReason() == RoadAddressGeocodingFailureReason.RATE_LIMIT_EXCEEDED) {
                return MyHomeComplexMappingReport.rateLimitedRows(
                        failureReport.failedSourceRowCount()
                );
            }
            return failureReport;
        }
        catch (RuntimeException exception) {
            log.warn(
                    "마이홈 단지 매핑 저장에 실패했습니다: sourceComplexIdentifier={}, sourceRowCount={}",
                    candidate.getSourceComplexIdentifier(),
                    sources.size(),
                    exception
            );
            if (!candidate.needsGeocoding()) {
                candidate.failMapping();
                candidateStore.save(candidate);
            }
            return recordFailure(
                    candidate.getSourceComplexIdentifier(),
                    sources,
                    MyHomeComplexMappingFailureReason.PERSISTENCE_ERROR,
                    PERSISTENCE_FAILURE_DETAIL,
                    occurredAt
            );
        }
    }

    private void resolveCoordinates(MyHomeComplexMappingCandidate candidate) {
        if (!candidate.needsGeocoding()) {
            return;
        }
        GeocodedRoadAddress address = candidateRepository
                .findFirstBySourceRoadAddressAndLatitudeIsNotNullOrderByIdAsc(candidate.getSourceRoadAddress())
                .map(MyHomeComplexMappingCandidate::geocodedAddress)
                .orElseGet(() -> geocodingService.geocode(candidate.getSourceRoadAddress()));
        candidate.resolve(address);
        candidateStore.save(candidate);
    }

    private MyHomeComplexMappingReport recordFailure(
            String sourceComplexIdentifier,
            List<MyHomeComplexSource> sources,
            MyHomeComplexMappingFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        List<MyHomeComplexMappingFailure> failures = new ArrayList<>();
        addFailures(failures, sources, sourceComplexIdentifier, reason, detail, occurredAt);
        failureStore.replaceForComplex(sourceComplexIdentifier, failures);
        return MyHomeComplexMappingReport.failedRows(failures.size());
    }

    private Map<String, List<MyHomeComplexSource>> sourcesFor(
            Collection<MyHomeComplexMappingCandidate> candidates
    ) {
        List<Long> sourceIdentifiers = candidates.stream()
                .map(MyHomeComplexMappingCandidate::getSourceComplexIdentifier)
                .map(identifier -> identifier.substring(0, identifier.indexOf(':')))
                .map(Long::valueOf)
                .toList();
        return groupSources(sourceRepository.findAllByHsmpSnIn(sourceIdentifiers), new ArrayList<>(), clock.instant());
    }

    private Map<String, List<MyHomeComplexSource>> groupSources(
            Collection<MyHomeComplexSource> sources,
            List<MyHomeComplexMappingFailure> failures,
            Instant occurredAt
    ) {
        Map<String, List<MyHomeComplexSource>> grouped = new LinkedHashMap<>();
        for (MyHomeComplexSource source : sources) {
            if (sourceMapper.shouldSkip(source)) {
                continue;
            }
            if (source.getHsmpSn() == null) {
                failures.add(failureOf(
                        source,
                        null,
                        MyHomeComplexMappingFailureReason.MISSING_REQUIRED_VALUE,
                        "단지 식별자 값이 없습니다.",
                        occurredAt
                ));
                continue;
            }
            try {
                String sourceComplexIdentifier = sourceMapper.sourceComplexIdentifier(source);
                grouped.computeIfAbsent(sourceComplexIdentifier, ignored -> new ArrayList<>()).add(source);
            }
            catch (MyHomeComplexMappingRejectedException exception) {
                failures.add(failureOf(
                        source,
                        String.valueOf(source.getHsmpSn()),
                        exception.reason(),
                        exception.getMessage(),
                        occurredAt
                ));
            }
        }
        return grouped;
    }

    private boolean hasProcessableCandidate() {
        return !candidateRepository.findAllByStatusInOrderByIdAsc(
                PROCESSABLE_STATUSES,
                PageRequest.of(0, 1)
        ).isEmpty();
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
                source.getSourceKey(),
                sourceComplexIdentifier,
                reason,
                detail,
                occurredAt
        );
    }

    private void validateBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("배치 크기는 1 이상 1000 이하여야 합니다.");
        }
    }

    private IngestAlreadyRunningException alreadyRunning() {
        log.warn("마이홈 단지 매핑이 이미 실행 중이므로 중복 실행을 건너뜁니다.");
        return new IngestAlreadyRunningException("마이홈 단지 매핑이 이미 실행 중입니다.");
    }
}
