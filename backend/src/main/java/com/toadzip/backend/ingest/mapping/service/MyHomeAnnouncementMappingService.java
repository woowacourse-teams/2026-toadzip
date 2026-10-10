package com.toadzip.backend.ingest.mapping.service;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;
import static com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock.Operation.MYHOME_ANNOUNCEMENT_MAPPING;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.exception.exception.AnnouncementRegistrationException;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.failure.service.IngestExecutionContext;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailure;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingFailureResponse;
import com.toadzip.backend.ingest.mapping.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureRepository;
import com.toadzip.backend.ingest.mapping.repository.MyHomeAnnouncementMappingFailureStore;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingWriter.MyHomeAnnouncementWriteResult;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementMappingWriter.MyHomeSupplyMatchingFailureData;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingData;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSourceMapper.MyHomeAnnouncementMappingRejectedException;
import com.toadzip.backend.ingest.mapping.service.MyHomeAnnouncementSupplyRowResolver.ResolvedAnnouncement;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MyHomeAnnouncementMappingService {

    private final MyHomeAnnouncementSourceReader sourceRepository;

    private final MyHomeAnnouncementMappingFailureRepository failureRepository;

    private final MyHomeAnnouncementMappingFailureStore failureStore;

    private final IngestOperationLock executionLock;

    private final AnnouncementRepository announcementRepository;

    private final MyHomeAnnouncementSourceMapper sourceMapper;

    private final MyHomeAnnouncementSupplyRowResolver supplyRowResolver;

    private final MyHomeAnnouncementMappingWriter writer;

    private final Clock clock;

    public MyHomeAnnouncementMappingReport mapAll() {
        return executionLock.tryRun(MYHOME_ANNOUNCEMENT_MAPPING, this::mapAllUnlocked)
                .orElseThrow(this::alreadyRunning);
    }

    public MyHomeAnnouncementMappingReport registerAnnouncement(String pblancId) {
        return executionLock.tryRun(MYHOME_ANNOUNCEMENT_MAPPING, () -> registerUnlocked(pblancId))
                .orElseThrow(this::alreadyRunning);
    }

    private MyHomeAnnouncementMappingReport registerUnlocked(String pblancId) {
        if (announcementRepository.findBySourceAnnouncementIdentifier(pblancId).isPresent()) {
            throw new AnnouncementRegistrationException("이미 등록된 공고입니다: " + pblancId);
        }
        var executionId = IngestExecutionContext.currentExecutionId().orElse(null);
        List<MyHomeAnnouncementSource> sources = sourceRepository.findAllByPblancIdOrderByIdAsc(pblancId).stream()
                .filter(source -> executionId == null || executionId.toString().equals(source.getLastSeenRunId()))
                .toList();
        if (sources.isEmpty()) {
            throw new AnnouncementRegistrationException("공고 원천을 찾을 수 없습니다: " + pblancId);
        }
        try {
            ResolvedAnnouncement resolved = supplyRowResolver.resolve(sourceMapper.map(sources));
            String previousId = resolved.data().previousSourceAnnouncementIdentifier();
            Announcement previous = null;
            if (previousId != null) {
                previous = announcementRepository.findBySourceAnnouncementIdentifier(previousId)
                        .orElseThrow(() -> new MyHomeAnnouncementMappingRejectedException(
                                MyHomeAnnouncementMappingFailureReason.PREVIOUS_ANNOUNCEMENT_NOT_FOUND,
                                "이전 공고가 등록되어 있지 않습니다: " + previousId));
            }
            MyHomeAnnouncementWriteResult result = writer.register(resolved, previous);
            return result.report();
        }
        catch (MyHomeAnnouncementMappingRejectedException exception) {
            List<MyHomeAnnouncementMappingFailure> failures = new ArrayList<>();
            reject(sources, exception.reason(), exception.getMessage(), failures, clock.instant());
            failureStore.reconcileForAnnouncement(pblancId, failures, executionId);
            throw new AnnouncementRegistrationException("정제 실패: " + exception.getMessage(), exception);
        }
        catch (DataIntegrityViolationException exception) {
            if (announcementRepository.findBySourceAnnouncementIdentifier(pblancId).isPresent()) {
                throw new AnnouncementRegistrationException("이미 등록된 공고입니다: " + pblancId, exception);
            }
            throw exception;
        }
    }

    private MyHomeAnnouncementMappingReport mapAllUnlocked() {
        Instant occurredAt = clock.instant();
        List<MyHomeAnnouncementMappingFailure> failures = new ArrayList<>();
        Map<String, List<MyHomeAnnouncementSource>> groupedSources = groupSources(failures, occurredAt);
        Set<String> processed = new LinkedHashSet<>();
        Set<String> processing = new LinkedHashSet<>();
        MyHomeAnnouncementMappingReport report = MyHomeAnnouncementMappingReport.failedRows(failures.size());
        for (String identifier : groupedSources.keySet()) {
            report = report.plus(mapGroup(
                    identifier,
                    groupedSources,
                    processed,
                    processing,
                    failures,
                    occurredAt
            ));
        }
        failureStore.reconcileAfterRun(
                failures,
                IngestExecutionContext.currentExecutionId().orElse(null)
        );
        return report;
    }

    private MyHomeAnnouncementMappingReport mapGroup(
            String identifier,
            Map<String, List<MyHomeAnnouncementSource>> groupedSources,
            Set<String> processed,
            Set<String> processing,
            List<MyHomeAnnouncementMappingFailure> failures,
            Instant occurredAt
    ) {
        if (processed.contains(identifier)) {
            return MyHomeAnnouncementMappingReport.empty();
        }
        List<MyHomeAnnouncementSource> sources = groupedSources.get(identifier);
        if (!processing.add(identifier)) {
            MyHomeAnnouncementMappingReport cycleReport = MyHomeAnnouncementMappingReport.empty();
            for (String processingIdentifier : processing) {
                processed.add(processingIdentifier);
                cycleReport = cycleReport.plus(reject(
                        groupedSources.get(processingIdentifier),
                        MyHomeAnnouncementMappingFailureReason.CYCLIC_ANNOUNCEMENT_REVISION,
                        "이전 공고 참조가 순환합니다.",
                        failures,
                        occurredAt
                ));
            }
            return cycleReport;
        }
        try {
            ResolvedAnnouncement resolved = supplyRowResolver.resolve(sourceMapper.map(sources));
            MyHomeAnnouncementMappingData data = resolved.data();
            String previousIdentifier = data.previousSourceAnnouncementIdentifier();
            MyHomeAnnouncementMappingReport previousReport = MyHomeAnnouncementMappingReport.empty();
            if (previousIdentifier != null && groupedSources.containsKey(previousIdentifier)) {
                previousReport = mapGroup(
                        previousIdentifier, groupedSources, processed, processing, failures, occurredAt
                );
            }
            if (processed.contains(identifier)) {
                return previousReport;
            }
            Announcement previous = previousIdentifier == null ? null : announcementRepository
                    .findBySourceAnnouncementIdentifier(previousIdentifier).orElse(null);
            if (previousIdentifier != null && previous == null) {
                processed.add(identifier);
                return previousReport.plus(reject(
                        sources,
                        MyHomeAnnouncementMappingFailureReason.PREVIOUS_ANNOUNCEMENT_NOT_FOUND,
                        "이전 공고를 찾을 수 없습니다: " + previousIdentifier,
                        failures,
                        occurredAt
                ));
            }
            MyHomeAnnouncementWriteResult result = writer.write(resolved, previous);
            addSupplyMatchingFailures(failures, result.failures(), occurredAt);
            processed.add(identifier);
            return previousReport.plus(result.report());
        }
        catch (MyHomeAnnouncementMappingRejectedException exception) {
            processed.add(identifier);
            return reject(sources, exception.reason(), exception.getMessage(), failures, occurredAt);
        }
        finally {
            processing.remove(identifier);
        }
    }

    private Map<String, List<MyHomeAnnouncementSource>> groupSources(
            List<MyHomeAnnouncementMappingFailure> failures,
            Instant occurredAt
    ) {
        Map<String, List<MyHomeAnnouncementSource>> grouped = new LinkedHashMap<>();
        for (MyHomeAnnouncementSource source : sourceRepository.findAll()) {
            String identifier = normalizedText(source.getPblancId());
            if (identifier == null) {
                failures.add(failureOf(
                        source,
                        MyHomeAnnouncementMappingFailureReason.MISSING_REQUIRED_VALUE,
                        "공고 식별자 값이 없습니다.",
                        occurredAt
                ));
                continue;
            }
            grouped.computeIfAbsent(identifier, ignored -> new ArrayList<>()).add(source);
        }
        return grouped;
    }

    private MyHomeAnnouncementMappingReport reject(
            List<MyHomeAnnouncementSource> sources,
            MyHomeAnnouncementMappingFailureReason reason,
            String detail,
            List<MyHomeAnnouncementMappingFailure> failures,
            Instant occurredAt
    ) {
        for (MyHomeAnnouncementSource source : sources) {
            failures.add(failureOf(source, reason, detail, occurredAt));
        }
        return MyHomeAnnouncementMappingReport.failedRows(sources.size());
    }

    private void addSupplyMatchingFailures(
            List<MyHomeAnnouncementMappingFailure> failures,
            List<MyHomeSupplyMatchingFailureData> matchingFailures,
            Instant occurredAt
    ) {
        for (MyHomeSupplyMatchingFailureData failure : matchingFailures) {
            failures.add(failureOf(failure.source(), failure.reason(), failure.detail(), occurredAt));
        }
    }

    private MyHomeAnnouncementMappingFailure failureOf(
            MyHomeAnnouncementSource source,
            MyHomeAnnouncementMappingFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        return MyHomeAnnouncementMappingFailure.create(
                source.getSourceKey(),
                source.getPblancId(),
                source.getHouseSn(),
                reason,
                detail,
                occurredAt
        );
    }

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementMappingFailureResponse> findFailures(int page, int size) {
        return failureRepository.findAllByStatusOrderBySourceKeyAscIdAsc(
                        PENDING,
                        PageRequest.of(page, size)
                )
                .stream()
                .map(MyHomeAnnouncementMappingFailureResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementMappingFailureResponse> findFailures() {
        return failureRepository.findAllByStatusOrderBySourceKeyAsc(PENDING)
                .stream()
                .map(MyHomeAnnouncementMappingFailureResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MyHomeAnnouncementMappingFailureResponse> findFailureHistory(int page, int size) {
        return failureRepository.findAllByOrderBySourceKeyAscIdAsc(PageRequest.of(page, size))
                .stream()
                .map(MyHomeAnnouncementMappingFailureResponse::from)
                .toList();
    }

    private String normalizedText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private IngestAlreadyRunningException alreadyRunning() {
        log.warn("마이홈 공고 매핑이 이미 실행 중이므로 중복 실행을 건너뜁니다.");
        return new IngestAlreadyRunningException("마이홈 공고 매핑이 이미 실행 중입니다.");
    }
}
