package com.toadzip.backend.ingest.collection.service;

import static com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock.Operation.SH_ANNOUNCEMENT_COLLECTION;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.ShAnnouncementSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.ShAnnouncementPage.Entry;
import com.toadzip.backend.ingest.collection.dto.ShAnnouncementPage;
import com.toadzip.backend.ingest.collection.repository.ShAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.ShAnnouncementStore;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.ShAnnouncementListParser;
import com.toadzip.backend.ingest.exception.exception.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.exception.exception.ExternalDataRetryInterruptedException;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ShAnnouncementCollectionService {

    private static final ExternalDataSource SOURCE = ExternalDataSource.SH_ANNOUNCEMENT;
    private static final int MAX_PAGES = 1_000;

    private final ShAnnouncementExternalRepository externalRepository;
    private final ShAnnouncementListParser parser;
    private final ShAnnouncementStore store;
    private final IngestOperationLock executionLock;
    private final ExternalDataRetryExecutor retryExecutor;
    private final ExternalDataFailureRecorder failureRecorder;
    private final Clock clock;
    private final int lookbackDays;
    private final Duration requestInterval;

    public ShAnnouncementCollectionService(
            ShAnnouncementExternalRepository externalRepository, ShAnnouncementListParser parser,
            ShAnnouncementStore store, IngestOperationLock executionLock, ExternalDataRetryExecutor retryExecutor,
            ExternalDataFailureRecorder failureRecorder, Clock clock,
            @Value("${ingest.sh.lookback-days:90}") int lookbackDays,
            @Value("${ingest.sh.request-interval:1s}") Duration requestInterval
    ) {
        if (lookbackDays < 0 || requestInterval.isNegative()) {
            throw new IllegalArgumentException("SH 조회 기간과 요청 간격은 음수일 수 없습니다.");
        }
        this.externalRepository = externalRepository;
        this.parser = parser;
        this.store = store;
        this.executionLock = executionLock;
        this.retryExecutor = retryExecutor;
        this.failureRecorder = failureRecorder;
        this.clock = clock;
        this.lookbackDays = lookbackDays;
        this.requestInterval = requestInterval;
    }

    public ExternalDataCollectionReport collect() {
        return executionLock.tryRun(SH_ANNOUNCEMENT_COLLECTION, this::collectUnlocked)
                .orElseThrow(() -> new IngestAlreadyRunningException("SH 공고 수집이 이미 실행 중입니다."));
    }

    private ExternalDataCollectionReport collectUnlocked() {
        LocalDate cutoff = LocalDate.MIN;
        if (lookbackDays > 0) {
            cutoff = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))).minusDays(lookbackDays);
        }
        Progress progress = new Progress();
        Set<String> seen = new HashSet<>();
        int totalCount = -1;
        IngestExecutionScope.beginWork("SH 공고 원천", "게시글", -1);
        for (int pageNumber = 1; pageNumber <= MAX_PAGES; pageNumber++) {
            ShAnnouncementPage page = fetchPage(pageNumber, totalCount, seen, progress);
            if (page == null) {
                break;
            }
            totalCount = page.totalCount();
            var selected = select(page, cutoff);
            for (Entry entry : selected) {
                collectDetail(entry, page, progress);
                IngestExecutionScope.workCompleted();
                if (progress.rateLimited > 0) {
                    return progress.report();
                }
            }
            if (pageNumber * 10 >= totalCount) {
                return progress.report();
            }
            if (pageNumber == MAX_PAGES) {
                recordFailure("page=" + (MAX_PAGES + 1),
                        new ExternalDataRequestException("SH 목록이 최대 페이지 수를 초과했습니다."), progress);
            }
        }
        return progress.report();
    }

    private List<Entry> select(ShAnnouncementPage page, LocalDate cutoff) {
        return page.entries().stream().filter(entry -> !entry.registeredDate().isBefore(cutoff)).toList();
    }

    private ShAnnouncementPage fetchPage(int pageNumber, int totalCount, Set<String> seen, Progress progress) {
        String request = "page=" + pageNumber;
        try {
            ShAnnouncementPage page = request(request,
                    () -> parser.parseList(externalRepository.fetchList(pageNumber), pageNumber), progress);
            if (totalCount >= 0 && totalCount != page.totalCount()) {
                throw new ExternalDataRequestException("수집 중 SH 목록 전체 건수가 바뀌었습니다. 다시 실행해 주세요.");
            }
            for (Entry entry : page.entries()) {
                if (!seen.add(entry.seq())) {
                    throw new ExternalDataRequestException("SH 페이지 사이에 중복 게시글이 있습니다. 다시 실행해 주세요.");
                }
            }
            failureRecorder.resolve(SOURCE, request);
            progress.successful++;
            return page;
        }
        catch (ExternalDataCallFailureException | ExternalDataRequestException exception) {
            recordFailure(request, exception, progress);
            return null;
        }
    }

    private void collectDetail(Entry entry, ShAnnouncementPage page, Progress progress) {
        String request = "seq=" + entry.seq();
        String detailHtml;
        try {
            detailHtml = request(request, () -> externalRepository.fetchDetail(entry.seq()), progress);
        }
        catch (ExternalDataCallFailureException | ExternalDataRequestException exception) {
            recordFailure(request, exception, progress);
            return;
        }
        IngestExecutionScope.verifyHeld();
        IngestExecutionScope.checkStopRequested();
        store.store(new ShAnnouncementSnapshot(entry.seq(), entry.title(), entry.department(), entry.registeredDate(),
                ShAnnouncementExternalRepository.detailUrl(entry.seq()),
                ShAnnouncementExternalRepository.LIST_URL + "&page=" + page.page(), page.rawHtml(), detailHtml));
        failureRecorder.resolve(SOURCE, request);
        progress.stored++;
        progress.successful++;
    }

    private <T> T request(String description, Supplier<T> action, Progress progress) {
        return retryExecutor.execute(SOURCE, description, () -> {
            try {
                Thread.sleep(requestInterval);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ExternalDataRetryInterruptedException(exception);
            }
            IngestExecutionScope.checkStopRequested();
            return action.get();
        }, progress.calls);
    }

    private void recordFailure(String request, RuntimeException exception, Progress progress) {
        failureRecorder.record(SOURCE, request, exception, log, "SH 공고 수집 실패");
        progress.failed++;
        progress.rateLimited += ExternalDataRateLimit.count(exception);
    }

    private static final class Progress {
        private final ExternalDataCallCounter calls = new ExternalDataCallCounter();
        private int stored;
        private int successful;
        private int failed;
        private int rateLimited;

        private ExternalDataCollectionReport report() {
            return new ExternalDataCollectionReport(SOURCE.operation(), stored, failed, calls.count(),
                    0, rateLimited, successful);
        }
    }
}
