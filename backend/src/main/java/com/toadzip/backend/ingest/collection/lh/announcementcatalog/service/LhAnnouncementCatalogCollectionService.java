package com.toadzip.backend.ingest.collection.lh.announcementcatalog.service;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.paging.service.PagedSourceCollector;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LhAnnouncementCatalogCollectionService {

    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 1_000;

    private final LhAnnouncementCatalogCollector collector;
    private final PagedSourceCollector pagedCollector;
    private final Clock clock;
    private final Period window;

    public LhAnnouncementCatalogCollectionService(
            LhAnnouncementCatalogCollector collector,
            PagedSourceCollector pagedCollector,
            Clock clock,
            @Value("${ingest.lh-announcement-catalog-window}") Period window
    ) {
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("LH 공고 목록 조회 기간은 양수여야 합니다.");
        }
        this.collector = collector;
        this.pagedCollector = pagedCollector;
        this.clock = clock;
        this.window = window;
    }

    public ExternalDataCollectionReport collect() {
        IngestExecutionScope.requireHeld();
        IngestExecutionScope.beginWork("LH 공고 목록", "페이지", -1);
        // 상시모집 공고는 게시일이 오래되어도 접수 중이므로 정한 조회 기간의 공고를 모두 받는다.
        LocalDate today = LocalDate.now(clock);
        return pagedCollector.collectAndReport(collector, startedAt -> new LhAnnouncementCatalogCollectionRequest(
                IngestExecutionScope.currentExecutionId().orElse(null), PAGE_SIZE, MAX_PAGES,
                today.minus(window), today, startedAt));
    }
}
