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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
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
        Set<String> verifiedIdentifiers = new LinkedHashSet<>();
        linkRepository.findAllByMergeIdIsNotNull().forEach(link ->
                verifiedIdentifiers.add(link.getSourceComplexIdentifier()));
        recordMissingLinkedSources(verifiedIdentifiers, groups, failures, occurredAt);

        MyHomeComplexMappingReport report = MyHomeComplexMappingReport.failedRows(failures.size());
        var executionId = IngestExecutionContext.currentExecutionId().orElse(null);
        try {
            for (Map.Entry<String, List<MyHomeComplexSource>> group : groups.entrySet()) {
                report = report.plus(mapGroup(group.getKey(), group.getValue(),
                        verifiedIdentifiers.contains(group.getKey()), failures, occurredAt));
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
            Set<String> verifiedIdentifiers,
            Map<String, List<MyHomeComplexSource>> groups,
            List<MyHomeComplexMappingFailure> failures,
            Instant occurredAt
    ) {
        verifiedIdentifiers.stream()
                .filter(identifier -> !groups.containsKey(identifier))
                .forEach(identifier -> failures.add(MyHomeComplexMappingFailure.create(
                        "linked-complex:" + identifier,
                        identifier,
                        MyHomeComplexMappingFailureReason.CONFLICTING_SOURCE_VALUE,
                        "확인된 연결 원천이 누락되어 기존 단지와 주택형을 보존합니다.",
                        occurredAt
                )));
    }

    private MyHomeComplexMappingReport mapGroup(
            String identifier,
            List<MyHomeComplexSource> sources,
            boolean verified,
            List<MyHomeComplexMappingFailure> failures,
            Instant occurredAt
    ) {
        if (verified) {
            return writeGroup(identifier, sources, failures, occurredAt, () -> writer.writeVerified(identifier));
        }

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
                case COORDINATE_CONVERSION_ERROR ->
                        MyHomeComplexMappingReport.operationalFailedRows(rejected.failedSourceRowCount());
                case INVALID_ADDRESS, ADDRESS_NOT_FOUND, COORDINATE_NOT_FOUND -> rejected;
            };
        }

        Address address = data.address().resolve(geocoded);
        return writeGroup(identifier, sources, failures, occurredAt, () -> writer.write(data, address));
    }

    private MyHomeComplexMappingReport writeGroup(
            String identifier,
            List<MyHomeComplexSource> sources,
            List<MyHomeComplexMappingFailure> failures,
            Instant occurredAt,
            Supplier<MyHomeComplexMappingReport> write
    ) {
        try {
            return write.get();
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
