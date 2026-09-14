package com.toadzip.backend.ingest.mapping.service;

import static com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingCandidateStatus.GEOCODED;
import static com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingCandidateStatus.PENDING;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.ingest.collection.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.location.domain.GeocodedRoadAddress;
import com.toadzip.backend.ingest.location.domain.RoadAddressGeocodingFailureReason;
import com.toadzip.backend.ingest.location.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.location.service.RoadAddressGeocodingService;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingCandidate;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingCandidateStatus;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailure;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingCandidateRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingCandidateStore;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingFailureStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Slf4j
@Component
class MyHomeComplexMappingBatchProcessor {

    private static final String PERSISTENCE_FAILURE_DETAIL =
            "단지와 주택형 저장 중 오류가 발생했습니다.";
    private static final Set<MyHomeComplexMappingCandidateStatus> PROCESSABLE_STATUSES = Set.of(
            PENDING,
            GEOCODED
    );

    private final MyHomeComplexMappingCandidateRepository candidateRepository;
    private final MyHomeComplexMappingCandidateStore candidateStore;
    private final MyHomeComplexMappingFailureStore failureStore;
    private final MyHomeComplexMappingPreparer preparer;
    private final MyHomeComplexSourceMapper sourceMapper;
    private final RoadAddressGeocodingService geocodingService;
    private final MyHomeComplexMappingWriter writer;
    private final Clock clock;

    MyHomeComplexMappingBatchProcessor(
            MyHomeComplexMappingCandidateRepository candidateRepository,
            MyHomeComplexMappingCandidateStore candidateStore,
            MyHomeComplexMappingFailureStore failureStore,
            MyHomeComplexMappingPreparer preparer,
            MyHomeComplexSourceMapper sourceMapper,
            RoadAddressGeocodingService geocodingService,
            MyHomeComplexMappingWriter writer,
            Clock clock
    ) {
        this.candidateRepository = candidateRepository;
        this.candidateStore = candidateStore;
        this.failureStore = failureStore;
        this.preparer = preparer;
        this.sourceMapper = sourceMapper;
        this.geocodingService = geocodingService;
        this.writer = writer;
        this.clock = clock;
    }

    MyHomeComplexMappingReport mapNext(int batchSize) {
        List<MyHomeComplexMappingCandidate> candidates = candidateRepository
                .findAllByStatusInOrderByIdAsc(
                        PROCESSABLE_STATUSES,
                        PageRequest.of(0, batchSize)
                );
        Map<String, List<MyHomeComplexSource>> groupedSources = preparer.sourcesFor(candidates);
        MyHomeComplexMappingReport report = MyHomeComplexMappingReport.failedRows(0);
        for (MyHomeComplexMappingCandidate candidate : candidates) {
            report = report.plus(mapCandidate(
                    candidate,
                    groupedSources.get(candidate.getSourceComplexIdentifier())
            ));
        }
        return report;
    }

    boolean hasProcessableCandidate() {
        return !candidateRepository.findAllByStatusInOrderByIdAsc(
                PROCESSABLE_STATUSES,
                PageRequest.of(0, 1)
        ).isEmpty();
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
        boolean needsGeocoding = candidate.needsGeocoding();
        try {
            MyHomeComplexMappingData data = sourceMapper.map(
                    candidate.getSourceComplexIdentifier(), sources
            );
            resolveCoordinates(candidate);
            Address address = data.address().resolve(candidate.geocodedAddress());
            MyHomeComplexMappingReport report = writer.write(data, address);
            completeCandidate(candidate);
            return report;
        }
        catch (MyHomeComplexMappingRejectedException exception) {
            candidateStore.delete(candidate);
            return recordFailure(
                    candidate.getSourceComplexIdentifier(), sources,
                    exception.reason(), exception.getMessage(), occurredAt
            );
        }
        catch (RoadAddressGeocodingException exception) {
            return handleGeocodingFailure(candidate, sources, exception, occurredAt);
        }
        catch (RuntimeException exception) {
            return handleUnexpectedFailure(
                    candidate, sources, needsGeocoding, exception, occurredAt
            );
        }
    }

    private void completeCandidate(MyHomeComplexMappingCandidate candidate) {
        candidate.markMapped();
        candidateStore.save(candidate);
        failureStore.replaceForComplex(candidate.getSourceComplexIdentifier(), List.of());
    }

    private MyHomeComplexMappingReport handleGeocodingFailure(
            MyHomeComplexMappingCandidate candidate,
            List<MyHomeComplexSource> sources,
            RoadAddressGeocodingException exception,
            Instant occurredAt
    ) {
        candidate.failGeocoding(exception.getReason());
        candidateStore.save(candidate);
        MyHomeComplexMappingReport report = recordFailure(
                candidate.getSourceComplexIdentifier(), sources,
                MyHomeComplexMappingFailureReason.GEOCODING_ERROR,
                "도로명주소 좌표 변환 실패: " + exception.getReason() + ", " + exception.getMessage(),
                occurredAt
        );
        if (exception.getReason() == RoadAddressGeocodingFailureReason.RATE_LIMIT_EXCEEDED) {
            return MyHomeComplexMappingReport.rateLimitedRows(report.failedSourceRowCount());
        }
        return report;
    }

    private MyHomeComplexMappingReport handleUnexpectedFailure(
            MyHomeComplexMappingCandidate candidate,
            List<MyHomeComplexSource> sources,
            boolean needsGeocoding,
            RuntimeException exception,
            Instant occurredAt
    ) {
        if (needsGeocoding && candidate.needsGeocoding()) {
            throw exception;
        }
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
                candidate.getSourceComplexIdentifier(), sources,
                MyHomeComplexMappingFailureReason.PERSISTENCE_ERROR,
                PERSISTENCE_FAILURE_DETAIL, occurredAt
        );
    }

    private void resolveCoordinates(MyHomeComplexMappingCandidate candidate) {
        if (!candidate.needsGeocoding()) {
            return;
        }
        GeocodedRoadAddress address = candidateRepository
                .findFirstBySourceRoadAddressAndLatitudeIsNotNullOrderByIdAsc(
                        candidate.getSourceRoadAddress()
                )
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
        for (MyHomeComplexSource source : sources) {
            failures.add(MyHomeComplexMappingFailure.create(
                    source.getSourceKey(), sourceComplexIdentifier, reason, detail, occurredAt
            ));
        }
        failureStore.replaceForComplex(sourceComplexIdentifier, failures);
        return MyHomeComplexMappingReport.failedRows(failures.size());
    }
}
