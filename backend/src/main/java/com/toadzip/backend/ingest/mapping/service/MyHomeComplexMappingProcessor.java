package com.toadzip.backend.ingest.mapping.service;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSource;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeComplexSourceReader;
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
import com.toadzip.backend.ingest.mapping.service.MyHomeComplexSourceMapper.SplitComplexKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class MyHomeComplexMappingProcessor {

    private static final Comparator<MappedGroup> SPLIT_MEMBER_ORDER = Comparator
            .comparingInt((MappedGroup member) -> member.data().totalHouseholdCount()).reversed()
            .thenComparingLong(MappedGroup::hsmpSn)
            .thenComparing(MappedGroup::identifier);

    private final MyHomeComplexSourceReader sourceRepository;
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
            Map<SplitComplexKey, List<MappedGroup>> splitGroups = new LinkedHashMap<>();
            for (Map.Entry<String, List<MyHomeComplexSource>> group : groups.entrySet()) {
                MappedGroup unmapped = new MappedGroup(group.getKey(), group.getValue(), null);
                if (verifiedIdentifiers.contains(group.getKey())) {
                    report = report.plus(writeGroup(List.of(unmapped), failures, occurredAt,
                            () -> writer.writeVerified(group.getKey())));
                    continue;
                }
                try {
                    MyHomeComplexMappingData data = sourceMapper.map(group.getKey(), group.getValue());
                    splitGroups.computeIfAbsent(data.splitKey(), ignored -> new ArrayList<>())
                            .add(new MappedGroup(group.getKey(), group.getValue(), data));
                }
                catch (MyHomeComplexMappingRejectedException exception) {
                    report = report.plus(reject(List.of(unmapped), exception.reason(), exception.getMessage(),
                            failures, occurredAt));
                }
            }
            Map<String, Long> linkedComplexIds = new HashMap<>();
            linkRepository.findAll().forEach(link ->
                    linkedComplexIds.put(link.getSourceComplexIdentifier(), link.getHousingComplex().getId()));
            Set<Long> sharedComplexIds = complexesLinkedToSeveralSplitGroups(splitGroups, linkedComplexIds);
            for (List<MappedGroup> members : splitGroups.values()) {
                boolean shared = members.stream()
                        .anyMatch(member -> sharedComplexIds.contains(linkedComplexIds.get(member.identifier())));
                report = report.plus(mapSplitGroup(members, shared, failures, occurredAt));
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
        failureStore.reconcileAfterRun(failures, executionId);
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

    private Set<Long> complexesLinkedToSeveralSplitGroups(
            Map<SplitComplexKey, List<MappedGroup>> splitGroups, Map<String, Long> linkedComplexIds
    ) {
        Map<Long, Set<SplitComplexKey>> owners = new HashMap<>();
        splitGroups.forEach((key, members) -> members.stream()
                .map(member -> linkedComplexIds.get(member.identifier()))
                .filter(Objects::nonNull)
                .forEach(complexId -> owners.computeIfAbsent(complexId, ignored -> new HashSet<>()).add(key)));
        return owners.entrySet().stream().filter(entry -> entry.getValue().size() > 1)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    private MyHomeComplexMappingReport mapSplitGroup(
            List<MappedGroup> unsorted,
            boolean sharedWithAnotherGroup,
            List<MyHomeComplexMappingFailure> failures,
            Instant occurredAt
    ) {
        List<MappedGroup> members = unsorted.stream().sorted(SPLIT_MEMBER_ORDER).toList();
        if (sharedWithAnotherGroup) {
            return reject(members, MyHomeComplexMappingFailureReason.CONFLICTING_SOURCE_VALUE,
                    "같은 단지에 연결된 원천의 단지 공통값이 서로 달라 단지 갱신을 보류합니다.", failures, occurredAt);
        }
        List<MyHomeComplexMappingData> data = members.stream().map(MappedGroup::data).toList();
        MyHomeComplexMappingData combined = data.size() == 1
                ? data.getFirst() : MyHomeComplexMappingData.combine(data);

        GeocodedRoadAddress geocoded;
        try {
            geocoded = geocodingService.geocode(combined.address().sourceRoadAddress());
        }
        catch (RoadAddressGeocodingException exception) {
            MyHomeComplexMappingReport rejected = reject(
                    members, MyHomeComplexMappingFailureReason.GEOCODING_ERROR,
                    "도로명주소 좌표 변환 실패: " + exception.getReason() + ", " + exception.getMessage(),
                    failures, occurredAt
            );
            return switch (exception.getReason()) {
                case COORDINATE_CONVERSION_ERROR ->
                        MyHomeComplexMappingReport.operationalFailedRows(rejected.failedSourceRowCount());
                case INVALID_ADDRESS, ADDRESS_NOT_FOUND, COORDINATE_NOT_FOUND -> rejected;
            };
        }

        Address address = combined.address().resolve(geocoded);
        if (data.size() == 1) {
            return writeGroup(members, failures, occurredAt, () -> writer.write(combined, address));
        }
        return writeGroup(members, failures, occurredAt, () -> writer.writeSplit(data, combined, address));
    }

    private MyHomeComplexMappingReport writeGroup(
            List<MappedGroup> members,
            List<MyHomeComplexMappingFailure> failures,
            Instant occurredAt,
            Supplier<MyHomeComplexMappingReport> write
    ) {
        try {
            return write.get();
        }
        catch (MyHomeComplexMappingRejectedException exception) {
            return reject(members, exception.reason(), exception.getMessage(), failures, occurredAt);
        }
        catch (RuntimeException exception) {
            log.warn("마이홈 단지 매핑 저장에 실패했습니다: sourceComplexIdentifiers={}, sourceRowCount={}",
                    members.stream().map(MappedGroup::identifier).toList(),
                    members.stream().mapToInt(member -> member.sources().size()).sum(), exception);
            MyHomeComplexMappingReport rejected = reject(
                    members, MyHomeComplexMappingFailureReason.PERSISTENCE_ERROR,
                    "단지와 주택형 저장 중 오류가 발생했습니다.", failures, occurredAt
            );
            return MyHomeComplexMappingReport.operationalFailedRows(rejected.failedSourceRowCount());
        }
    }

    private MyHomeComplexMappingReport reject(
            List<MappedGroup> members,
            MyHomeComplexMappingFailureReason reason,
            String detail,
            List<MyHomeComplexMappingFailure> failures,
            Instant occurredAt
    ) {
        int rows = 0;
        for (MappedGroup member : members) {
            for (MyHomeComplexSource source : member.sources()) {
                failures.add(failureOf(source, member.identifier(), reason, detail, occurredAt));
            }
            rows += member.sources().size();
        }
        return MyHomeComplexMappingReport.failedRows(rows);
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

    private record MappedGroup(String identifier, List<MyHomeComplexSource> sources, MyHomeComplexMappingData data) {

        long hsmpSn() {
            return Long.parseLong(identifier.substring(0, identifier.indexOf(':')));
        }
    }
}
