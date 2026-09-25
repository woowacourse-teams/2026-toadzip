package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementCatalogPage;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementCatalogPage.Entry;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCatalogStore;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCatalogStore.StoreResult;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionExecutionLock;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementCatalogResponseParser;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LhAnnouncementCatalogCollectionService {

    private static final ExternalDataSource SOURCE = ExternalDataSource.LH_ANNOUNCEMENT_CATALOG;
    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 1_000;

    private final LhAnnouncementExternalRepository externalRepository;
    private final LhAnnouncementCatalogResponseParser parser;
    private final LhAnnouncementCatalogStore store;
    private final LhAnnouncementCollectionExecutionLock executionLock;
    private final ExternalDataRetryExecutor retryExecutor;
    private final ExternalDataFailureRecorder failureRecorder;
    private final MeterRegistry meterRegistry;

    public ExternalDataCollectionReport collect() {
        return executionLock.tryRun(SOURCE, this::collectUnlocked)
                .orElseThrow(() -> new IngestAlreadyRunningException("LH 공고 목록 수집이 이미 실행 중입니다."));
    }

    private ExternalDataCollectionReport collectUnlocked() {
        ExternalDataCallCounter counter = new ExternalDataCallCounter();
        List<Entry> entries;
        try {
            entries = fetchAll(counter);
        }
        catch (ExternalDataCallFailureException | ExternalDataRequestException exception) {
            failureRecorder.record(SOURCE, "PG_SZ=" + PAGE_SIZE, exception, log, "LH 공고 목록 수집 실패");
            return new ExternalDataCollectionReport(
                    SOURCE.operation(), 0, 1, counter.count(), 0, ExternalDataRateLimit.count(exception)
            );
        }
        StoreResult result = store.store(entries);
        failureRecorder.resolveStartingWith(SOURCE, "PG_SZ=" + PAGE_SIZE);
        meterRegistry.counter("ingest.announcement.catalog.rows", "change", "new")
                .increment(result.newRowCount());
        meterRegistry.counter("ingest.announcement.catalog.rows", "change", "changed")
                .increment(result.changedRowCount());
        meterRegistry.counter("ingest.announcement.catalog.rows", "change", "unchanged")
                .increment(result.unchangedRowCount());
        log.info("LH 공고 목록 수집 완료: storedRowCount={}, newRowCount={}, changedRowCount={}, "
                        + "unchangedRowCount={}, externalApiCallCount={}",
                result.storedRowCount(), result.newRowCount(), result.changedRowCount(),
                result.unchangedRowCount(), counter.count());
        return new ExternalDataCollectionReport(SOURCE.operation(), result.storedRowCount(), 0, counter.count());
    }

    private List<Entry> fetchAll(ExternalDataCallCounter counter) {
        List<Entry> entries = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        LhAnnouncementCatalogPage first = null;
        for (int page = 1; page <= MAX_PAGES; page++) {
            LhAnnouncementCatalogPage current = fetchPage(page, counter);
            if (first == null) {
                first = current;
            }
            validateConsistentWindow(first, current);
            for (Entry entry : current.entries()) {
                if (!keys.add(entry.snapshot().sourceKey())) {
                    throw new ExternalDataRequestException("LH 목록에 중복 공고 식별자가 있습니다.");
                }
            }
            entries.addAll(current.entries());
            if (entries.size() == first.totalCount()) {
                return List.copyOf(entries);
            }
            if (current.entries().size() != PAGE_SIZE || entries.size() > first.totalCount()) {
                throw new ExternalDataRequestException("LH 목록의 전체 건수와 수집 행 수가 다릅니다.");
            }
        }
        throw new ExternalDataRequestException("LH 공고 목록이 최대 페이지 수를 초과했습니다.");
    }

    private LhAnnouncementCatalogPage fetchPage(int page, ExternalDataCallCounter counter) {
        return retryExecutor.execute(
                SOURCE, "PG_SZ=" + PAGE_SIZE + "&PAGE=" + page,
                () -> parser.parse(externalRepository.fetchCatalog(page, PAGE_SIZE).body(), page, PAGE_SIZE),
                counter
        );
    }

    private void validateConsistentWindow(LhAnnouncementCatalogPage first, LhAnnouncementCatalogPage current) {
        if (first.totalCount() != current.totalCount() || !first.startDate().equals(current.startDate())
                || !first.endDate().equals(current.endDate())) {
            throw new ExternalDataRequestException("LH 목록의 검색 기간 또는 전체 건수가 페이지 사이에 변경됐습니다.");
        }
    }
}
