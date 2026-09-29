package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.ingest.collection.domain.MyHomeComplexSource;
import com.toadzip.backend.ingest.collection.repository.MyHomeComplexSourceRepository;
import com.toadzip.backend.ingest.failure.service.IngestExecutionContext;
import com.toadzip.backend.ingest.location.domain.GeocodedRoadAddress;
import com.toadzip.backend.ingest.location.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.location.service.RoadAddressGeocodingService;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailure;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import com.toadzip.backend.ingest.mapping.dto.MyHomeComplexMappingReport;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexLinkRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeComplexMappingFailureStore;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexSourceMapper.MyHomeComplexMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexSourceMapper.MyHomeComplexMappingRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class MyHomeComplexMappingProcessor {

    private final MyHomeComplexSourceRepository sourceRepository;
    private final MyHomeComplexLinkRepository linkRepository;
    private final MyHomeComplexMappingFailureStore failureStore;
    private final MyHomeComplexSourceMapper sourceMapper;
    private final RoadAddressGeocodingService geocodingService;
    private final MyHomeComplexMappingWriter writer;
    private final Clock clock;

    MyHomeComplexMappingReport mapAll() {
        Instant occurredAt = clock.instant();
        List<MyHomeComplexMappingFailure> failures = new ArrayList<>();
        Map<String, List<MyHomeComplexSource>> groups = groupSources(failures, occurredAt);
        recordMissingLinkedSources(groups, failures, occurredAt);

        MyHomeComplexMappingReport report = MyHomeComplexMappingReport.failedRows(failures.size());
        var executionId = IngestExecutionContext.currentExecutionId().orElse(null);
        try {
            for (Map.Entry<String, List<MyHomeComplexSource>> group : groups.entrySet()) {
                report = report.plus(mapGroup(group.getKey(), group.getValue(), failures, occurredAt));
            }
        }
        catch (RuntimeException exception) {
            try {
                failureStore.recordObserved(failures, executionId);
            }
            catch (RuntimeException recordingFailure) {
                exception.addSuppressed(recordingFailure);
            }
            throw exception;
        }
        failureStore.replaceAll(failures, executionId);
        return report;
    }

    private Map<String, List<MyHomeComplexSource>> groupSources(
            List<MyHomeComplexMappingFailure> failures, Instant occurredAt
    ) {
        Map<String, List<MyHomeComplexSource>> groups = new LinkedHashMap<>();
        for (MyHomeComplexSource source : sourceRepository.findAll()) {
            if (sourceMapper.shouldSkip(source)) {
                continue;
            }
            if (source.getHsmpSn() == null) {
                failures.add(failureOf(source, null,
                        MyHomeComplexMappingFailureReason.MISSING_REQUIRED_VALUE,
                        "단지 식별자 값이 없습니다.", occurredAt));
                continue;
            }
            try {
                String identifier = sourceMapper.sourceComplexIdentifier(source);
                groups.computeIfAbsent(identifier, ignored -> new ArrayList<>()).add(source);
            }
            catch (MyHomeComplexMappingRejectedException exception) {
                failures.add(failureOf(source, String.valueOf(source.getHsmpSn()),
                        exception.reason(), exception.getMessage(), occurredAt));
            }
        }
        return groups;
    }

    private void recordMissingLinkedSources(
            Map<String, List<MyHomeComplexSource>> groups,
            List<MyHomeComplexMappingFailure> failures,
            Instant occurredAt
    ) {
        linkRepository.findAllByMergeIdIsNotNull().stream()
                .filter(link -> !groups.containsKey(link.getSourceComplexIdentifier()))
                .forEach(link -> failures.add(MyHomeComplexMappingFailure.create(
                        "linked-complex:" + link.getSourceComplexIdentifier(),
                        link.getSourceComplexIdentifier(),
                        MyHomeComplexMappingFailureReason.CONFLICTING_SOURCE_VALUE,
                        "확인된 연결 원천이 누락되어 기존 단지와 주택형을 보존합니다.",
                        occurredAt
                )));
    }

    private MyHomeComplexMappingReport mapGroup(
            String identifier,
            List<MyHomeComplexSource> sources,
            List<MyHomeComplexMappingFailure> failures,
            Instant occurredAt
    ) {
        MyHomeComplexMappingData data;
        try {
            data = sourceMapper.map(identifier, sources);
        }
        catch (MyHomeComplexMappingRejectedException exception) {
            return reject(identifier, sources, exception.reason(), exception.getMessage(), failures, occurredAt);
        }

        GeocodedRoadAddress geocoded;
        try {
            geocoded = geocodingService.geocode(data.address().sourceRoadAddress());
        }
        catch (RoadAddressGeocodingException exception) {
            MyHomeComplexMappingReport rejected = reject(
                    identifier, sources, MyHomeComplexMappingFailureReason.GEOCODING_ERROR,
                    "도로명주소 좌표 변환 실패: " + exception.getReason() + ", " + exception.getMessage(),
                    failures, occurredAt
            );
            return switch (exception.getReason()) {
                case RATE_LIMIT_EXCEEDED ->
                        MyHomeComplexMappingReport.rateLimitedRows(rejected.failedSourceRowCount());
                case EXTERNAL_API_ERROR, COORDINATE_CONVERSION_ERROR, NOT_CONFIGURED ->
                        MyHomeComplexMappingReport.operationalFailedRows(rejected.failedSourceRowCount());
                case INVALID_ADDRESS, ADDRESS_NOT_FOUND, AMBIGUOUS_ADDRESS, COORDINATE_NOT_FOUND -> rejected;
            };
        }

        Address address = data.address().resolve(geocoded);
        try {
            return writer.write(data, address);
        }
        catch (MyHomeComplexMappingRejectedException exception) {
            return reject(identifier, sources, exception.reason(), exception.getMessage(), failures, occurredAt);
        }
        catch (RuntimeException exception) {
            log.warn("마이홈 단지 매핑 저장에 실패했습니다: sourceComplexIdentifier={}, sourceRowCount={}",
                    identifier, sources.size(), exception);
            MyHomeComplexMappingReport rejected = reject(
                    identifier, sources, MyHomeComplexMappingFailureReason.PERSISTENCE_ERROR,
                    "단지와 주택형 저장 중 오류가 발생했습니다.", failures, occurredAt
            );
            return MyHomeComplexMappingReport.operationalFailedRows(rejected.failedSourceRowCount());
        }
    }

    private MyHomeComplexMappingReport reject(
            String identifier,
            List<MyHomeComplexSource> sources,
            MyHomeComplexMappingFailureReason reason,
            String detail,
            List<MyHomeComplexMappingFailure> failures,
            Instant occurredAt
    ) {
        for (MyHomeComplexSource source : sources) {
            failures.add(failureOf(source, identifier, reason, detail, occurredAt));
        }
        return MyHomeComplexMappingReport.failedRows(sources.size());
    }

    private MyHomeComplexMappingFailure failureOf(
            MyHomeComplexSource source,
            String identifier,
            MyHomeComplexMappingFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        return MyHomeComplexMappingFailure.create(
                source.getSourceKey(), identifier, reason, detail, occurredAt
        );
    }
}
