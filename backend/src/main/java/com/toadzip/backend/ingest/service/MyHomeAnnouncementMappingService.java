package com.toadzip.backend.ingest.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementMappingFailure;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementMappingFailureResponse;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementMappingReport;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.repository.MyHomeAnnouncementMappingExecutionLock;
import com.toadzip.backend.ingest.repository.MyHomeAnnouncementMappingFailureRepository;
import com.toadzip.backend.ingest.repository.MyHomeAnnouncementMappingFailureStore;
import com.toadzip.backend.ingest.repository.MyHomeAnnouncementSourceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class MyHomeAnnouncementMappingService {

    private final MyHomeAnnouncementSourceRepository sourceRepository;

    private final MyHomeAnnouncementMappingFailureRepository failureRepository;

    private final MyHomeAnnouncementMappingFailureStore failureStore;

    private final MyHomeAnnouncementMappingExecutionLock executionLock;

    private final AnnouncementRepository announcementRepository;

    private final MyHomeAnnouncementSourceMapper sourceMapper;

    private final MyHomeAnnouncementMappingWriter writer;

    private final Clock clock;

    public MyHomeAnnouncementMappingService(
            MyHomeAnnouncementSourceRepository sourceRepository,
            MyHomeAnnouncementMappingFailureRepository failureRepository,
            MyHomeAnnouncementMappingFailureStore failureStore,
            MyHomeAnnouncementMappingExecutionLock executionLock,
            AnnouncementRepository announcementRepository,
            MyHomeAnnouncementSourceMapper sourceMapper,
            MyHomeAnnouncementMappingWriter writer,
            Clock clock
    ) {
        this.sourceRepository = sourceRepository;
        this.failureRepository = failureRepository;
        this.failureStore = failureStore;
        this.executionLock = executionLock;
        this.announcementRepository = announcementRepository;
        this.sourceMapper = sourceMapper;
        this.writer = writer;
        this.clock = clock;
    }

    public MyHomeAnnouncementMappingReport mapAll() {
        return executionLock.tryRun(this::mapAllUnlocked)
                .orElseThrow(this::alreadyRunning);
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
        failureStore.replaceAll(failures);
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
            processed.add(identifier);
            return reject(
                    sources,
                    MyHomeAnnouncementMappingFailureReason.CYCLIC_ANNOUNCEMENT_REVISION,
                    "이전 공고 참조가 순환합니다.",
                    failures,
                    occurredAt
            );
        }
        try {
            MyHomeAnnouncementMappingData data = sourceMapper.map(sources);
            PreviousAnnouncementResult previousResult = previousAnnouncementOf(
                    data,
                    groupedSources,
                    processed,
                    processing,
                    failures,
                    occurredAt
            );
            if (processed.contains(identifier)) {
                return previousResult.report();
            }
            if (data.previousSourceAnnouncementIdentifier() != null
                    && previousResult.announcement() == null) {
                processed.add(identifier);
                return previousResult.report().plus(reject(
                        sources,
                        MyHomeAnnouncementMappingFailureReason.PREVIOUS_ANNOUNCEMENT_NOT_FOUND,
                        "이전 공고를 찾을 수 없습니다: " + data.previousSourceAnnouncementIdentifier(),
                        failures,
                        occurredAt
                ));
            }
            MyHomeAnnouncementWriteResult result = writer.write(data, previousResult.announcement());
            addSupplyMatchingFailures(failures, result.failures(), occurredAt);
            processed.add(identifier);
            return previousResult.report().plus(result.report());
        }
        catch (MyHomeAnnouncementMappingRejectedException exception) {
            processed.add(identifier);
            return reject(sources, exception.reason(), exception.getMessage(), failures, occurredAt);
        }
        finally {
            processing.remove(identifier);
        }
    }

    private PreviousAnnouncementResult previousAnnouncementOf(
            MyHomeAnnouncementMappingData data,
            Map<String, List<MyHomeAnnouncementSource>> groupedSources,
            Set<String> processed,
            Set<String> processing,
            List<MyHomeAnnouncementMappingFailure> failures,
            Instant occurredAt
    ) {
        String previousIdentifier = data.previousSourceAnnouncementIdentifier();
        if (previousIdentifier == null) {
            return PreviousAnnouncementResult.empty();
        }
        MyHomeAnnouncementMappingReport report = MyHomeAnnouncementMappingReport.empty();
        if (groupedSources.containsKey(previousIdentifier)) {
            report = mapGroup(
                    previousIdentifier,
                    groupedSources,
                    processed,
                    processing,
                    failures,
                    occurredAt
            );
        }
        Announcement previous = announcementRepository
                .findBySourceAnnouncementIdentifier(previousIdentifier)
                .orElse(null);
        return new PreviousAnnouncementResult(previous, report);
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
    public List<MyHomeAnnouncementMappingFailureResponse> findFailures() {
        return failureRepository.findAllByOrderBySourceKeyAsc()
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

    private record PreviousAnnouncementResult(
            Announcement announcement,
            MyHomeAnnouncementMappingReport report
    ) {

        static PreviousAnnouncementResult empty() {
            return new PreviousAnnouncementResult(null, MyHomeAnnouncementMappingReport.empty());
        }
    }
}
