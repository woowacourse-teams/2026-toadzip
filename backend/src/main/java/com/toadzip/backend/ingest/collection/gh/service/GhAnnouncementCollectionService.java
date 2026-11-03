package com.toadzip.backend.ingest.collection.gh.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementBoard;
import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementSnapshot;
import com.toadzip.backend.ingest.collection.gh.dto.GhAnnouncementContent;
import com.toadzip.backend.ingest.collection.gh.dto.GhAnnouncementPage;
import com.toadzip.backend.ingest.collection.gh.dto.GhAnnouncementPage.Entry;
import com.toadzip.backend.ingest.collection.gh.repository.GhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.gh.repository.GhAnnouncementHtmlParser;
import com.toadzip.backend.ingest.collection.gh.repository.GhAnnouncementListParser;
import com.toadzip.backend.ingest.collection.gh.repository.GhAnnouncementStore;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallCounter;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.collection.service.ExternalDataRateLimit;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
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

/** GH주택청약센터 임대주택·매입임대 게시판을 차례로 돌며 공고 원천을 수집한다. */
@Slf4j
@Service
public class GhAnnouncementCollectionService {

    private static final ExternalDataSource SOURCE = ExternalDataSource.GH_ANNOUNCEMENT;
    private static final int MAX_PAGES = 1_000;

    private final GhAnnouncementExternalRepository externalRepository;
    private final GhAnnouncementListParser parser;
    private final GhAnnouncementHtmlParser detailParser;
    private final GhAnnouncementStore store;
    private final ExternalDataRetryExecutor retryExecutor;
    private final ExternalDataFailureRecorder failureRecorder;
    private final Clock clock;
    private final int lookbackDays;
    private final Duration requestInterval;

    public GhAnnouncementCollectionService(
            GhAnnouncementExternalRepository externalRepository, GhAnnouncementListParser parser,
            GhAnnouncementHtmlParser detailParser,
            GhAnnouncementStore store, ExternalDataRetryExecutor retryExecutor,
            ExternalDataFailureRecorder failureRecorder, Clock clock,
            @Value("${ingest.gh.lookback-days:90}") int lookbackDays,
            @Value("${ingest.gh.request-interval:1s}") Duration requestInterval
    ) {
        if (lookbackDays < 0 || requestInterval.isNegative()) {
            throw new IllegalArgumentException("GH 조회 기간과 요청 간격은 음수일 수 없습니다.");
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
        // 공고 번호는 두 게시판이 함께 쓰는 번호라서 게시판을 넘어 중복을 확인한다.
        Set<String> seen = new HashSet<>();
        IngestExecutionScope.beginWork("GH 공고 원천", "공고", -1);
        for (GhAnnouncementBoard board : GhAnnouncementBoard.values()) {
            collectBoard(board, cutoff, seen, progress);
            if (progress.rateLimited > 0) {
                break;
            }
        }
        return progress.report();
    }

    private void collectBoard(GhAnnouncementBoard board, LocalDate cutoff, Set<String> seen, Progress progress) {
        int totalCount = -1;
        for (int pageNumber = 1; pageNumber <= MAX_PAGES; pageNumber++) {
            GhAnnouncementPage page = fetchPage(board, pageNumber, totalCount, seen, progress);
            if (page == null) {
                return;
            }
            totalCount = page.totalCount();
            List<Entry> selected = page.entries().stream()
                    .filter(entry -> !entry.postedDate().isBefore(cutoff)).toList();
            Set<String> collected = alreadyCollected(board, selected);
            if (!collected.isEmpty()) {
                IngestExecutionScope.verifyCanContinue();
                store.updateListValues(selected.stream().filter(entry -> collected.contains(entry.pbancNo())).toList());
            }
            for (Entry entry : selected) {
                if (collected.contains(entry.pbancNo())) {
                    progress.skipped++;
                }
                else {
                    collectDetail(board, entry, page, progress);
                }
                IngestExecutionScope.workCompleted();
                if (progress.rateLimited > 0) {
                    return;
                }
            }
            if (pageNumber * 10 >= totalCount) {
                return;
            }
            if (pageNumber == MAX_PAGES) {
                recordFailure(pageRequest(board, MAX_PAGES + 1),
                        new ExternalDataRequestException("GH 목록이 최대 페이지 수를 초과했습니다."), progress);
            }
        }
    }

    /** 제목·게시일이 그대로이고 열린 실패가 없는 공고는 다시 받지 않는다. 일부만 읽어 저장한 공고는 실패가 열려 있어 다시 받는다. */
    private Set<String> alreadyCollected(GhAnnouncementBoard board, List<Entry> entries) {
        if (entries.isEmpty()) {
            return Set.of();
        }
        Set<String> unchanged = new HashSet<>(store.findUnchangedPbancNos(board, entries));
        if (unchanged.isEmpty()) {
            return unchanged;
        }
        Set<String> pending = failureRecorder.findPendingRequestDescriptions(SOURCE,
                unchanged.stream().map(GhAnnouncementCollectionService::detailRequest).toList());
        unchanged.removeIf(pbancNo -> pending.contains(detailRequest(pbancNo)));
        return unchanged;
    }

    private static String detailRequest(String pbancNo) {
        return "pbancNo=" + pbancNo;
    }

    private GhAnnouncementPage fetchPage(
            GhAnnouncementBoard board, int pageNumber, int totalCount, Set<String> seen, Progress progress
    ) {
        String request = pageRequest(board, pageNumber);
        try {
            GhAnnouncementPage page = request(request,
                    () -> parser.parseList(externalRepository.fetchList(board, pageNumber), board, pageNumber),
                    progress);
            if (totalCount >= 0 && totalCount != page.totalCount()) {
                throw new ExternalDataRequestException("수집 중 GH 목록 전체 건수가 바뀌었습니다. 다시 실행해 주세요.");
            }
            for (Entry entry : page.entries()) {
                if (!seen.add(entry.pbancNo())) {
                    throw new ExternalDataRequestException("GH 페이지 사이에 중복 공고가 있습니다. 다시 실행해 주세요.");
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

    private void collectDetail(GhAnnouncementBoard board, Entry entry, GhAnnouncementPage page, Progress progress) {
        String request = detailRequest(entry.pbancNo());
        GhAnnouncementContent content;
        try {
            String html = request(request,
                    () -> externalRepository.fetchDetail(board, entry.pbancNo(), entry.kindCode()), progress);
            content = detailParser.parse(html, board, entry);
        }
        catch (ExternalDataCallFailureException | ExternalDataRequestException exception) {
            recordFailure(request, exception, progress);
            return;
        }
        IngestExecutionScope.verifyCanContinue();
        store.store(new GhAnnouncementSnapshot(board, entry.pbancNo(), entry.kindCode(), content.title(),
                content.supplyType(), entry.region(), content.postedDate(), entry.closingDate(), entry.status(),
                board.detailUrl(entry.pbancNo(), entry.kindCode()), board.listUrl(page.page()),
                content.bodyText(), content.attachments()));
        progress.stored++;
        if (!content.unreadParts().isEmpty()) {
            recordFailure(request, new ExternalDataRequestException("GH 상세 일부를 읽지 못해 읽은 내용만 저장했습니다: "
                    + String.join(", ", content.unreadParts())), progress);
            return;
        }
        failureRecorder.resolve(SOURCE, request);
        progress.successful++;
    }

    private String pageRequest(GhAnnouncementBoard board, int pageNumber) {
        return "board=" + board.code() + "&page=" + pageNumber;
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
        failureRecorder.record(SOURCE, request, exception, log, "GH 공고 수집 실패");
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
