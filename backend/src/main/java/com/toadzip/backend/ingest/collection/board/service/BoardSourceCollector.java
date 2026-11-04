package com.toadzip.backend.ingest.collection.board.service;

import com.toadzip.backend.ingest.collection.board.dto.BoardDetail;
import com.toadzip.backend.ingest.collection.board.dto.BoardEntry;
import com.toadzip.backend.ingest.collection.board.dto.BoardPage;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 기관 게시판을 첫 페이지부터 넘기며 조회 기간 안의 대상 글을 상세까지 받아 저장한다. 목록 값이 저장값과 같고 다 읽었으며
 * 열린 실패가 없고 마지막 수집 뒤 재수집 기한이 지나지 않은 글은 상세를 다시 받지 않는다. 수집 중 전체 건수가 바뀌거나
 * 페이지 사이에 같은 글이 나오면 그 페이지에서 멈춘다. 요청 실패는 요청 단위로 기록하고 다음 글을 계속하며, 호출 제한에
 * 걸리면 남은 요청을 보내지 않는다.
 */
@Slf4j
@Component
public class BoardSourceCollector {

    private static final int MAX_PAGES = 1_000;
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final ExternalDataRetryExecutor retryExecutor;
    private final ExternalDataFailureRecorder failureRecorder;
    private final Clock clock;
    private final Duration refetchAfter;

    /** refetchAfter는 목록 값이 그대로여도 상세를 다시 받는 기한이다. 기관이 목록에 드러내지 않고 고친 상세를 따라잡는다. */
    public BoardSourceCollector(
            ExternalDataRetryExecutor retryExecutor, ExternalDataFailureRecorder failureRecorder, Clock clock,
            @Value("${ingest.board-announcement.refetch-after:7d}") Duration refetchAfter
    ) {
        if (refetchAfter.isNegative()) {
            throw new IllegalArgumentException("게시판 공고 재수집 기한은 음수일 수 없습니다.");
        }
        this.retryExecutor = retryExecutor;
        this.failureRecorder = failureRecorder;
        this.clock = clock;
        this.refetchAfter = refetchAfter;
    }

    /** lookbackDays가 0이면 게시일과 관계없이 모든 글을 본다. */
    public <E extends BoardEntry, S> ExternalDataCollectionReport collect(
            BoardSource<E, S> board, int lookbackDays, Duration requestInterval
    ) {
        LocalDate cutoff = LocalDate.MIN;
        if (lookbackDays > 0) {
            cutoff = LocalDate.now(clock.withZone(KOREA)).minusDays(lookbackDays);
        }
        Run<E, S> run = new Run<>(board, new BoardRequests(board.source(), retryExecutor, requestInterval));
        IngestExecutionScope.beginWork(board.agency() + " 공고 원천", board.unit(), -1);
        run.collect(cutoff);
        return run.report();
    }

    private final class Run<E extends BoardEntry, S> {

        private final BoardSource<E, S> board;
        private final BoardRequests requests;
        private final Set<String> seen = new HashSet<>();
        private int totalCount = -1;
        private int stored;
        private int successful;
        private int failed;
        private int skipped;

        private Run(BoardSource<E, S> board, BoardRequests requests) {
            this.board = board;
            this.requests = requests;
        }

        private void collect(LocalDate cutoff) {
            for (int pageNumber = 1; pageNumber <= MAX_PAGES; pageNumber++) {
                BoardPage<E> page = fetchPage(pageNumber);
                if (page == null || !collectPage(page, cutoff) || pageNumber * board.pageSize() >= totalCount) {
                    return;
                }
            }
            recordFailure(board.pageRequest(MAX_PAGES + 1),
                    new ExternalDataRequestException(board.agency() + " 목록이 최대 페이지 수를 초과했습니다."));
        }

        /** 페이지의 대상 글을 처리하고, 호출 제한에 걸려 멈춰야 하면 false를 돌려준다. */
        private boolean collectPage(BoardPage<E> page, LocalDate cutoff) {
            List<E> selected = page.entries().stream().filter(entry -> !entry.postedDate().isBefore(cutoff))
                    .filter(board::isTarget).toList();
            Map<String, Instant> unchanged = unchanged(selected);
            Set<String> skippable = skippable(unchanged);
            for (E entry : selected) {
                collectEntry(entry, skippable, page.page());
                IngestExecutionScope.workCompleted();
                if (requests.rateLimited()) {
                    return false;
                }
            }
            return true;
        }

        private void collectEntry(E entry, Set<String> skippable, int page) {
            if (skippable.contains(entry.key())) {
                skipped++;
                return;
            }
            collectDetail(entry, page);
        }

        /**
         * 목록 값이 저장값과 같고 다 읽은 글의 마지막 수집 시각이다. 이 글들은 상세를 다시 받다 실패해도 목록에서 바뀌는
         * 값을 놓치지 않도록 먼저 목록 값을 반영한다.
         */
        private Map<String, Instant> unchanged(List<E> entries) {
            if (entries.isEmpty()) {
                return Map.of();
            }
            Map<String, Instant> unchanged = board.findUnchangedCollectedAt(entries);
            if (!unchanged.isEmpty()) {
                IngestExecutionScope.verifyCanContinue();
                board.updateListValues(entries.stream().filter(entry -> unchanged.containsKey(entry.key())).toList());
            }
            return unchanged;
        }

        /** 상세를 다시 받지 않을 글이다. 일부만 읽어 저장한 글은 실패가 열려 있어 다시 받는다. */
        private Set<String> skippable(Map<String, Instant> unchanged) {
            Instant refetchFrom = clock.instant().minus(refetchAfter);
            Set<String> skippable = unchanged.entrySet().stream()
                    .filter(collected -> collected.getValue().isAfter(refetchFrom)).map(Map.Entry::getKey)
                    .collect(Collectors.toCollection(HashSet::new));
            if (skippable.isEmpty()) {
                return skippable;
            }
            Set<String> pending = failureRecorder.findPendingRequestDescriptions(board.source(),
                    skippable.stream().map(board::detailRequest).toList());
            skippable.removeIf(key -> pending.contains(board.detailRequest(key)));
            return skippable;
        }

        private BoardPage<E> fetchPage(int pageNumber) {
            String request = board.pageRequest(pageNumber);
            try {
                BoardPage<E> page = requests.send(request, () -> board.fetchPage(pageNumber));
                verifyConsistent(page);
                totalCount = page.totalCount();
                failureRecorder.resolve(board.source(), request);
                successful++;
                return page;
            }
            catch (ExternalDataCallFailureException | ExternalDataRequestException exception) {
                recordFailure(request, exception);
                return null;
            }
        }

        private void verifyConsistent(BoardPage<E> page) {
            if (totalCount >= 0 && totalCount != page.totalCount()) {
                throw new ExternalDataRequestException(
                        "수집 중 " + board.agency() + " 목록 전체 건수가 바뀌었습니다. 다시 실행해 주세요.");
            }
            for (E entry : page.entries()) {
                if (!seen.add(entry.key())) {
                    throw new ExternalDataRequestException(board.agency() + " 페이지 사이에 중복된 " + board.unit()
                            + " 번호가 있습니다. 다시 실행해 주세요.");
                }
            }
        }

        private void collectDetail(E entry, int page) {
            String request = board.detailRequest(entry.key());
            BoardDetail<S> detail;
            try {
                detail = board.readDetail(entry, page, requests);
            }
            catch (ExternalDataCallFailureException | ExternalDataRequestException exception) {
                recordFailure(request, exception);
                return;
            }
            IngestExecutionScope.verifyCanContinue();
            board.store(detail.snapshot());
            stored++;
            if (!detail.unreadParts().isEmpty()) {
                recordFailure(request, new ExternalDataRequestException(board.agency()
                        + " 상세 일부를 읽지 못해 읽은 내용만 저장했습니다: " + String.join(", ", detail.unreadParts())));
                return;
            }
            failureRecorder.resolve(board.source(), request);
            successful++;
        }

        private void recordFailure(String request, RuntimeException exception) {
            failureRecorder.record(board.source(), request, exception, log, board.agency() + " 공고 수집 실패");
            failed++;
        }

        private ExternalDataCollectionReport report() {
            return new ExternalDataCollectionReport(board.source().operation(), stored, failed, requests.callCount(),
                    skipped, requests.rateLimitedCount(), successful);
        }
    }
}
