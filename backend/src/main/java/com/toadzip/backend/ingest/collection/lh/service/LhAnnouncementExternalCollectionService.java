package com.toadzip.backend.ingest.collection.lh.service;

import static com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementSharedRequestCollector.sourceSelectionDescription;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCandidateSelector.CandidateSelection;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Resolution;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Skipped;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementCurrentSources;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import com.toadzip.backend.ingest.collection.myhome.announcement.repository.MyHomeAnnouncementSourceReader;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LhAnnouncementExternalCollectionService {

    private static final int ANNOUNCEMENT_BATCH_SIZE = 500;

    private final MyHomeAnnouncementSourceReader myHomeAnnouncementRepository;
    private final ExternalDataFailureRecorder failureRecorder;
    private final LhAnnouncementCollectionCandidateResolver candidateResolver;
    private final LhAnnouncementCandidateSelector candidateSelector;
    private final LhAnnouncementSharedRequestCollector sharedRequests;
    private final MeterRegistry meterRegistry;

    public ExternalDataCollectionReport collect(ExternalDataSource targetSource) {
        validateTargetSource(targetSource);
        log.info("{} 수집을 시작합니다.", targetSource.operation());
        ExternalDataCollectionReport report = collectAnnouncements(targetSource);
        log.info(
                "{} 수집을 완료했습니다: storedRowCount={}, failedRequestCount={}, "
                        + "externalApiCallCount={}, skippedRequestCount={}",
                targetSource.operation(),
                report.storedRowCount(),
                report.failedRequestCount(),
                report.externalApiCallCount(),
                report.skippedRequestCount()
        );
        return report;
    }

    public ExternalDataCollectionReport refresh(ExternalDataSource targetSource, String pblancId) {
        return refresh(targetSource, pblancId, null);
    }

    /** 공고목록에서 정한 수집 후보 하나를 다시 받는다. 마이홈 공고가 없는 LH 공고의 공급 교체 승인 뒤에 쓴다. */
    public ExternalDataCollectionReport refreshCatalogTarget(ExternalDataSource targetSource, String requestDescription) {
        validateTargetSource(targetSource);
        List<Candidate> candidates = candidateResolver.resolveCatalogRequests().stream()
                .filter(candidate -> candidate.requestDescription().equals(requestDescription))
                .toList();
        if (candidates.isEmpty()) {
            throw new InvalidIngestRequestException("최신 공고목록에 있는 LH 공고의 수집 요청이 아닙니다.");
        }
        return collectCandidates(targetSource, candidates, new HashMap<>(), true);
    }

    public ExternalDataCollectionReport refresh(
            ExternalDataSource targetSource, String pblancId, UUID sourceExecutionId
    ) {
        validateTargetSource(targetSource);
        validatePblancId(pblancId);
        log.info("{} 강제 갱신을 시작합니다: pblancId={}", targetSource.operation(), pblancId);
        ExternalDataCollectionReport report = refreshAnnouncement(targetSource, pblancId.strip(), sourceExecutionId);
        log.info(
                "{} 강제 갱신을 완료했습니다: pblancId={}, storedRowCount={}, failedRequestCount={}, "
                        + "externalApiCallCount={}, skippedRequestCount={}",
                targetSource.operation(),
                pblancId,
                report.storedRowCount(),
                report.failedRequestCount(),
                report.externalApiCallCount(),
                report.skippedRequestCount()
        );
        return report;
    }

    private ExternalDataCollectionReport collectAnnouncements(ExternalDataSource targetSource) {
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(targetSource.operation());
        Set<String> handledAnnouncementKeys = new HashSet<>();
        Set<String> loadedAnnouncementKeys = new HashSet<>();
        Map<String, Boolean> completedRequests = new HashMap<>();
        Set<String> myHomeRequests = new HashSet<>();
        long lastSeenId = 0L;
        while (true) {
            IngestExecutionScope.checkStopRequested();
            List<MyHomeAnnouncementSourceView> sources = findNextBatch(targetSource, lastSeenId);
            if (sources.isEmpty()) {
                return report.plus(collectCatalogTargets(targetSource, myHomeRequests, completedRequests));
            }
            lastSeenId = sources.getLast().getId();
            List<MyHomeAnnouncementSourceView> completeSources = completeSourceGroups(
                    targetSource, sources, loadedAnnouncementKeys
            );
            if (completeSources.isEmpty()) {
                continue;
            }
            ExternalDataCollectionReport batchReport = collectBatch(
                    targetSource,
                    completeSources,
                    handledAnnouncementKeys,
                    myHomeRequests,
                    completedRequests,
                    false
            );
            report = report.plus(batchReport);
            if (batchReport.rateLimitedRequestCount() > 0) {
                return report;
            }
        }
    }

    /**
     * 마이홈 원천을 모두 처리한 뒤 공고목록에만 있는 임대주택 공고를 수집한다. 이번 실행에서 마이홈 원천이 가리킨 요청은
     * 수집했든 종료 정책·충돌로 뺐든 건너뛴다. 마이홈 공고의 보강 원천은 마이홈 기준으로만 바뀌어야 하기 때문이다.
     */
    private ExternalDataCollectionReport collectCatalogTargets(
            ExternalDataSource targetSource,
            Set<String> myHomeRequests,
            Map<String, Boolean> completedRequests
    ) {
        IngestExecutionScope.checkStopRequested();
        List<Candidate> candidates = candidateResolver.resolveCatalogTargets().stream()
                .filter(candidate -> !myHomeRequests.contains(candidate.requestDescription()))
                .toList();
        return collectCandidates(targetSource, candidates, completedRequests, false);
    }

    private ExternalDataCollectionReport refreshAnnouncement(
            ExternalDataSource targetSource,
            String pblancId,
            UUID sourceExecutionId
    ) {
        List<MyHomeAnnouncementSourceView> sources = readSources(targetSource, true,
                () -> myHomeAnnouncementRepository.findAllByPblancIdOrderByIdAsc(pblancId).stream()
                        .filter(source -> sourceExecutionId == null
                                || sourceExecutionId.toString().equals(source.getLastSeenRunId())).toList());
        if (sources.isEmpty()) {
            throw new InvalidIngestRequestException("마이홈 공고 원천을 찾을 수 없습니다: pblancId=" + pblancId);
        }
        return collectBatch(targetSource, sources, new HashSet<>(), new HashSet<>(), new HashMap<>(), true);
    }

    private List<MyHomeAnnouncementSourceView> findNextBatch(ExternalDataSource targetSource, long lastSeenId) {
        return readSources(targetSource, false,
                () -> myHomeAnnouncementRepository.findByIdGreaterThanOrderByIdAsc(
                        lastSeenId, PageRequest.of(0, ANNOUNCEMENT_BATCH_SIZE)
                ));
    }

    private List<MyHomeAnnouncementSourceView> completeSourceGroups(
            ExternalDataSource targetSource,
            List<MyHomeAnnouncementSourceView> batch,
            Set<String> loadedAnnouncementKeys
    ) {
        List<MyHomeAnnouncementSourceView> unread = batch.stream()
                .filter(source -> loadedAnnouncementKeys.add(sourceGroupKey(source)))
                .toList();
        if (unread.isEmpty()) {
            return List.of();
        }
        List<String> identifiers = unread.stream()
                .map(MyHomeAnnouncementSourceView::getPblancId)
                .filter(identifier -> identifier != null && !identifier.isBlank())
                .distinct()
                .toList();
        List<MyHomeAnnouncementSourceView> sources = new ArrayList<>(measurePreparation(
                targetSource, false, "source_group_read",
                () -> myHomeAnnouncementRepository.findAllByPblancIdInOrderByIdAsc(identifiers)
        ));
        unread.stream()
                .filter(source -> source.getPblancId() == null || source.getPblancId().isBlank())
                .forEach(sources::add);
        return sources;
    }

    private List<MyHomeAnnouncementSourceView> currentSources(List<MyHomeAnnouncementSourceView> sources) {
        return sources.stream()
                .collect(Collectors.groupingBy(this::sourceGroupKey, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .flatMap(group -> MyHomeAnnouncementCurrentSources.select(group).stream())
                .toList();
    }

    private String sourceGroupKey(MyHomeAnnouncementSourceView source) {
        if (source.getPblancId() == null || source.getPblancId().isBlank()) {
            return "source:" + source.getSourceKey();
        }
        return source.getPblancId();
    }

    private List<MyHomeAnnouncementSourceView> readSources(
            ExternalDataSource targetSource,
            boolean forceRefresh,
            Supplier<List<MyHomeAnnouncementSourceView>> query
    ) {
        List<MyHomeAnnouncementSourceView> sources = measurePreparation(targetSource, forceRefresh, "source_read", query);
        recordCount(targetSource, forceRefresh, "source.rows", "read", sources.size());
        return sources;
    }

    private ExternalDataCollectionReport collectBatch(
            ExternalDataSource targetSource,
            List<MyHomeAnnouncementSourceView> sources,
            Set<String> handledAnnouncementKeys,
            Set<String> myHomeRequests,
            Map<String, Boolean> completedRequests,
            boolean forceRefresh
    ) {
        List<MyHomeAnnouncementSourceView> current = measurePreparation(
                targetSource, forceRefresh, "current_source_selection", () -> currentSources(sources)
        );
        List<Resolution> resolutions = measurePreparation(targetSource, forceRefresh, "candidate_resolution",
                () -> candidateResolver.resolveAll(current));
        resolutions.stream().filter(Candidate.class::isInstance).map(Candidate.class::cast)
                .map(Candidate::requestDescription).forEach(myHomeRequests::add);
        CandidateSelection selection = measurePreparation(targetSource, forceRefresh, "candidate_selection",
                () -> candidateSelector.select(current, resolutions, handledAnnouncementKeys, forceRefresh));
        recordSelection(targetSource, forceRefresh, selection, sources.size() - current.size());
        ExternalDataCollectionReport report = selectionReport(targetSource, selection);
        return report.plus(collectCandidates(
                targetSource, selection.candidates(), completedRequests, forceRefresh
        ));
    }

    private void recordSelection(
            ExternalDataSource targetSource,
            boolean forceRefresh,
            CandidateSelection selection,
            int historicalSourceCount
    ) {
        recordCount(targetSource, forceRefresh, "source.rows", "candidate", selection.candidates().size());
        recordCount(targetSource, forceRefresh, "source.rows", "unsupported", selection.skipped().size());
        recordCount(targetSource, forceRefresh, "source.rows", "policy_excluded", selection.policyExcludedCount());
        recordCount(targetSource, forceRefresh, "source.rows", "duplicate", selection.duplicateCount());
        recordCount(targetSource, forceRefresh, "source.rows", "historical", historicalSourceCount);
        recordCount(targetSource, forceRefresh, "candidates", "conflicting", selection.conflicts().size());
    }

    private ExternalDataCollectionReport selectionReport(
            ExternalDataSource targetSource,
            CandidateSelection selection
    ) {
        failureRecorder.skipAll(targetSource,
                selection.sourceKeysWithoutEligibleCandidates().stream()
                        .map(LhAnnouncementSharedRequestCollector::sourceSelectionDescription).toList(),
                "현재 마이홈 공고 원천이 모두 LH 수집 대상에서 제외되어 충돌 재처리를 건너뜁니다.");
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(targetSource.operation());
        for (Candidate conflict : selection.conflicts()) {
            report = report.plus(conflictReport(targetSource, conflict));
        }
        for (Skipped skipped : selection.skipped()) {
            report = report.plus(skipReport(targetSource, skipped.sourceDescription(), skipped.reason()));
        }
        return report;
    }

    private ExternalDataCollectionReport conflictReport(ExternalDataSource targetSource, Candidate conflict) {
        failureRecorder.record(targetSource, sourceSelectionDescription(conflict.sourceAnnouncementKey()),
                new IllegalStateException("현재 마이홈 공고 원천의 LH 요청이 서로 다릅니다: pblancId="
                        + conflict.sourceAnnouncementKey()), log, "LH 현재 원천 선택 실패");
        return new ExternalDataCollectionReport(targetSource.operation(), 0, 1, 0, 0, 0, 0, 1);
    }

    private ExternalDataCollectionReport collectCandidates(
            ExternalDataSource targetSource,
            List<Candidate> candidates,
            Map<String, Boolean> completedRequests,
            boolean forceRefresh
    ) {
        if (candidates.isEmpty()) {
            return ExternalDataCollectionReport.empty(targetSource.operation());
        }
        Map<String, List<Candidate>> candidatesByRequest = candidates.stream()
                .collect(Collectors.groupingBy(
                        Candidate::requestDescription, LinkedHashMap::new, Collectors.toList()
                ));
        Set<String> pendingConflicts = measurePreparation(targetSource, forceRefresh, "failure_read",
                () -> failureRecorder.findPendingRequestDescriptions(targetSource, candidates.stream()
                        .map(candidate -> sourceSelectionDescription(candidate.sourceAnnouncementKey())).toList()));
        List<List<Candidate>> pending = new ArrayList<>();
        for (List<Candidate> requestCandidates : candidatesByRequest.values()) {
            IngestExecutionScope.verifyCanContinue();
            Boolean successful = completedRequests.get(requestCandidates.getFirst().requestDescription());
            if (successful == null) {
                pending.add(requestCandidates);
                continue;
            }
            if (successful) {
                sharedRequests.link(targetSource, requestCandidates, pendingConflicts);
            }
        }
        int pendingCandidateCount = pending.stream().mapToInt(List::size).sum();
        recordCount(targetSource, forceRefresh, "candidates", "refresh", pendingCandidateCount);
        recordCount(targetSource, forceRefresh, "requests", "refresh", pending.size());
        recordCount(targetSource, forceRefresh, "requests", "execution_reused",
                candidatesByRequest.size() - pending.size());
        IngestExecutionScope.beginWork(
                targetSource.operation() + " · 현재 묶음 (최대 500개 원천 행)", "요청", pending.size()
        );
        return sharedRequests.collect(targetSource, pending, completedRequests, pendingConflicts);
    }

    private <T> T measurePreparation(
            ExternalDataSource targetSource,
            boolean forceRefresh,
            String phase,
            Supplier<T> action
    ) {
        return meterRegistry.timer("ingest.announcement.prepare",
                        "source", targetSource.name(), "mode", collectionMode(forceRefresh), "phase", phase)
                .record(action);
    }

    private void recordCount(
            ExternalDataSource targetSource,
            boolean forceRefresh,
            String name,
            String result,
            int count
    ) {
        meterRegistry.counter("ingest.announcement." + name,
                        "source", targetSource.name(), "mode", collectionMode(forceRefresh), "result", result)
                .increment(count);
    }

    private String collectionMode(boolean forceRefresh) {
        if (forceRefresh) {
            return "forced";
        }
        return "scheduled";
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

    private void validatePblancId(String pblancId) {
        if (pblancId == null || pblancId.isBlank()) {
            throw new InvalidIngestRequestException("공고 식별자는 필수입니다.");
        }
    }

}
