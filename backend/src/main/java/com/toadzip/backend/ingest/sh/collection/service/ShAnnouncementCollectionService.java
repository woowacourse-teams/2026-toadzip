package com.toadzip.backend.ingest.sh.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.sh.collection.domain.ShAnnouncementSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.sh.collection.dto.ShAnnouncementContent;
import com.toadzip.backend.ingest.sh.collection.dto.ShAnnouncementPage.Entry;
import com.toadzip.backend.ingest.sh.collection.dto.ShAnnouncementPage;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementExternalRepository;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementStore;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementListParser;
import com.toadzip.backend.ingest.sh.collection.repository.ShAnnouncementHtmlParser;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallCounter;
import com.toadzip.backend.ingest.collection.service.ExternalDataRateLimit;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryInterruptedException;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
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
    private final ShAnnouncementHtmlParser detailParser;
    private final ShAnnouncementStore store;
    private final ExternalDataRetryExecutor retryExecutor;
    private final ExternalDataFailureRecorder failureRecorder;
    private final Clock clock;
    private final int lookbackDays;
    private final Duration requestInterval;

    public ShAnnouncementCollectionService(
            ShAnnouncementExternalRepository externalRepository, ShAnnouncementListParser parser,
            ShAnnouncementHtmlParser detailParser,
            ShAnnouncementStore store, ExternalDataRetryExecutor retryExecutor,
            ExternalDataFailureRecorder failureRecorder, Clock clock,
            @Value("${ingest.sh.lookback-days:90}") int lookbackDays,
            @Value("${ingest.sh.request-interval:1s}") Duration requestInterval
    ) {
        if (lookbackDays < 0 || requestInterval.isNegative()) {
            throw new IllegalArgumentException("SH 조회 기간과 요청 간격은 음수일 수 없습니다.");
        }
        this.externalRepository = externalRepository;
        this.parser = parser;
        this.detailParser = detailParser;
        this.store = store;
        this.retryExecutor = retryExecutor;
        this.failureRecorder = failureRecorder;
        this.clock = clock;
        this.lookbackDays = lookbackDays;
        this.requestInterval = requestInterval;
    }

    public ExternalDataCollectionReport collect() {
        IngestExecutionScope.requireHeld();
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
            Set<String> collected = alreadyCollected(selected);
            for (Entry entry : selected) {
                if (collected.contains(entry.seq())) {
                    progress.skipped++;
                }
                else {
                    collectDetail(entry, page, progress);
                }
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

    /** 제목·등록일이 그대로이고 열린 실패가 없는 게시글은 다시 받지 않는다. 일부만 읽어 저장한 글은 실패가 열려 있어 다시 받는다. */
    private Set<String> alreadyCollected(List<Entry> entries) {
        if (entries.isEmpty()) {
            return Set.of();
        }
        Set<String> unchanged = new HashSet<>(store.findUnchangedSeqs(entries));
        if (unchanged.isEmpty()) {
            return unchanged;
        }
        Set<String> pending = failureRecorder.findPendingRequestDescriptions(SOURCE,
                unchanged.stream().map(ShAnnouncementCollectionService::detailRequest).toList());
        unchanged.removeIf(seq -> pending.contains(detailRequest(seq)));
        return unchanged;
    }

    private static String detailRequest(String seq) {
        return "seq=" + seq;
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
        String request = detailRequest(entry.seq());
        ShAnnouncementContent content;
        try {
            String html = request(request, () -> externalRepository.fetchDetail(entry.seq()), progress);
            content = detailParser.parse(html, entry.seq(), entry.title(), entry.registeredDate());
        }
        catch (ExternalDataCallFailureException | ExternalDataRequestException exception) {
            recordFailure(request, exception, progress);
            return;
        }
        IngestExecutionScope.verifyCanContinue();
        String department = content.department() == null ? entry.department() : content.department();
        store.store(new ShAnnouncementSnapshot(entry.seq(), content.title(), department, content.registeredDate(),
                ShAnnouncementExternalRepository.detailUrl(entry.seq()),
                ShAnnouncementExternalRepository.LIST_URL + "&page=" + page.page(), content.bodyText(), content.attachments()));
        progress.stored++;
        if (!content.unreadParts().isEmpty()) {
            recordFailure(request, new ExternalDataRequestException("SH 상세 일부를 읽지 못해 읽은 내용만 저장했습니다: "
                    + String.join(", ", content.unreadParts())), progress);
            return;
        }
        failureRecorder.resolve(SOURCE, request);
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
        private int skipped;

        private ExternalDataCollectionReport report() {
            return new ExternalDataCollectionReport(SOURCE.operation(), stored, failed, calls.count(),
                    skipped, rateLimited, successful);
        }
    }
}
