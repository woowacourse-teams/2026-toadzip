package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionExecutionLock;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionProgressStore.BatchProgress;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementSourceRepository;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Resolution;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Skipped;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LhAnnouncementExternalCollectionService {

    private static final int ANNOUNCEMENT_BATCH_SIZE = 500;

    private final MyHomeAnnouncementSourceRepository myHomeAnnouncementRepository;
    private final LhAnnouncementCollectionExecutionLock executionLock;
    private final LhAnnouncementCollectionProgressManager progressManager;
    private final ExternalDataFailureRecorder failureRecorder;
    private final LhAnnouncementCollectionCandidateResolver candidateResolver;
    private final LhAnnouncementCandidateCollector candidateCollector;

    public LhAnnouncementExternalCollectionService(
            MyHomeAnnouncementSourceRepository myHomeAnnouncementRepository,
            LhAnnouncementCollectionExecutionLock executionLock,
            LhAnnouncementCollectionProgressManager progressManager,
            ExternalDataFailureRecorder failureRecorder,
            LhAnnouncementCollectionCandidateResolver candidateResolver,
            LhAnnouncementCandidateCollector candidateCollector
    ) {
        this.myHomeAnnouncementRepository = myHomeAnnouncementRepository;
        this.executionLock = executionLock;
        this.progressManager = progressManager;
        this.failureRecorder = failureRecorder;
        this.candidateResolver = candidateResolver;
        this.candidateCollector = candidateCollector;
    }

    public ExternalDataCollectionReport collect(ExternalDataSource targetSource) {
        validateTargetSource(targetSource);
        log.info("{} 수집을 시작합니다.", targetSource.operation());
        ExternalDataCollectionReport report = executionLock
                .tryRun(targetSource, () -> collectAnnouncements(targetSource))
                .orElseThrow(() -> alreadyRunning(targetSource));
        log.info(
                "{} 수집을 완료했습니다: storedRowCount={}, failedRequestCount={}, externalApiCallCount={}, skippedRequestCount={}",
                targetSource.operation(),
                report.storedRowCount(),
                report.failedRequestCount(),
                report.externalApiCallCount(),
                report.skippedRequestCount()
        );
        return report;
    }

    private ExternalDataCollectionReport collectAnnouncements(ExternalDataSource targetSource) {
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(targetSource.operation());
        Set<String> visitedSourceAnnouncements = new HashSet<>();
        Set<String> attemptedRequests = new HashSet<>();
        long lastSeenId = 0L;
        while (true) {
            List<MyHomeAnnouncementSource> sources = findNextBatch(lastSeenId);
            if (sources.isEmpty()) {
                return report;
            }
            lastSeenId = sources.getLast().getId();
            ExternalDataCollectionReport batchReport = collectBatch(
                    targetSource,
                    sources,
                    visitedSourceAnnouncements,
                    attemptedRequests
            );
            report = report.plus(batchReport);
            if (batchReport.rateLimitedRequestCount() > 0) {
                return report;
            }
        }
    }

    private List<MyHomeAnnouncementSource> findNextBatch(long lastSeenId) {
        return myHomeAnnouncementRepository.findByIdGreaterThanOrderByIdAsc(
                lastSeenId,
                PageRequest.of(0, ANNOUNCEMENT_BATCH_SIZE)
        );
    }

    private ExternalDataCollectionReport collectBatch(
            ExternalDataSource targetSource,
            List<MyHomeAnnouncementSource> sources,
            Set<String> visitedSourceAnnouncements,
            Set<String> attemptedRequests
    ) {
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(targetSource.operation());
        List<Candidate> candidates = new ArrayList<>();
        for (MyHomeAnnouncementSource source : sources) {
            Resolution resolution = candidateResolver.resolve(source);
            if (!visitedSourceAnnouncements.add(resolution.sourceAnnouncementKey())) {
                continue;
            }
            if (resolution instanceof Skipped skipped) {
                report = report.plus(skipReport(
                        targetSource,
                        skipped.sourceDescription(),
                        skipped.reason()
                ));
                continue;
            }
            Candidate candidate = (Candidate) resolution;
            if (!attemptedRequests.add(candidate.requestDescription())) {
                continue;
            }
            candidates.add(candidate);
        }
        return report.plus(collectCandidates(targetSource, candidates));
    }

    private ExternalDataCollectionReport collectCandidates(
            ExternalDataSource targetSource,
            List<Candidate> candidates
    ) {
        if (candidates.isEmpty()) {
            return ExternalDataCollectionReport.empty(targetSource.operation());
        }
        BatchProgress progress = progressManager.findBatch(targetSource, candidates);
        Set<String> storedPanIds = new HashSet<>(progress.storedPanIds());
        Set<String> historyPanIds = new HashSet<>(progress.historyPanIds());
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(targetSource.operation());
        for (Candidate candidate : candidates) {
            ExternalDataCollectionReport candidateReport = collectCandidate(
                    targetSource,
                    candidate,
                    progress,
                    storedPanIds,
                    historyPanIds
            );
            report = report.plus(candidateReport);
            if (candidateReport.rateLimitedRequestCount() > 0) {
                return report;
            }
        }
        return report;
    }

    private ExternalDataCollectionReport collectCandidate(
            ExternalDataSource targetSource,
            Candidate candidate,
            BatchProgress progress,
            Set<String> storedPanIds,
            Set<String> historyPanIds
    ) {
        if (progress.isCompleted(candidate.requestDescription())) {
            return ExternalDataCollectionReport.empty(targetSource.operation());
        }
        if (storedPanIds.contains(candidate.panId()) && !historyPanIds.contains(candidate.panId())) {
            progressManager.complete(targetSource, candidate);
            historyPanIds.add(candidate.panId());
            return ExternalDataCollectionReport.empty(targetSource.operation());
        }
        ExternalDataCollectionReport report = candidateCollector.collect(targetSource, candidate);
        if (report.failedRequestCount() == 0) {
            storedPanIds.add(candidate.panId());
            historyPanIds.add(candidate.panId());
        }
        return report;
    }

    private ExternalDataCollectionReport skipReport(
            ExternalDataSource targetSource,
            String requestDescription,
            String skipReason
    ) {
        failureRecorder.skip(targetSource, requestDescription, skipReason);
        return new ExternalDataCollectionReport(targetSource.operation(), 0, 0, 0, 1);
    }

    private void validateTargetSource(ExternalDataSource targetSource) {
        boolean supported = targetSource == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL
                || targetSource == ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY;
        if (!supported) {
            throw new IllegalArgumentException("LH 공고 API가 아닙니다.");
        }
    }

    private IngestAlreadyRunningException alreadyRunning(ExternalDataSource targetSource) {
        log.warn("{} 수집이 이미 실행 중이므로 중복 실행을 건너뜁니다.", targetSource.operation());
        return new IngestAlreadyRunningException(targetSource.operation() + " 수집이 이미 실행 중입니다.");
    }

}
