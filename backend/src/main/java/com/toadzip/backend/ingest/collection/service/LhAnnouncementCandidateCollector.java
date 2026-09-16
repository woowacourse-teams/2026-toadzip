package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.LhSourceStore;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementPageFetcher.FetchedPages;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LhAnnouncementCandidateCollector {

    private final LhAnnouncementPageFetcher pageFetcher;
    private final LhSourceStore sourceStore;
    private final ExternalDataFailureRecorder failureRecorder;
    private final LhAnnouncementCollectionProgressManager progressManager;

    public ExternalDataCollectionReport collect(ExternalDataSource targetSource, Candidate candidate) {
        LhAnnouncementRequest request = candidate.request();
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        StoredPages storedPages;
        try {
            storedPages = collectAndStore(targetSource, request, callCounter);
        }
        catch (ExternalDataCallFailureException exception) {
            return failedReport(targetSource, request, exception, callCounter);
        }
        progressManager.complete(targetSource, candidate);
        storedPages.requestDescriptions().forEach(description -> failureRecorder.resolve(targetSource, description));
        return new ExternalDataCollectionReport(
                targetSource.operation(),
                storedPages.storedRowCount(),
                0,
                callCounter.count()
        );
    }

    private ExternalDataCollectionReport failedReport(
            ExternalDataSource targetSource,
            LhAnnouncementRequest request,
            RuntimeException exception,
            ExternalDataCallCounter callCounter
    ) {
        failureRecorder.record(
                targetSource,
                request.requestDescription(),
                exception,
                log,
                "LH 외부 API 수집에 실패했습니다"
        );
        return new ExternalDataCollectionReport(
                targetSource.operation(),
                0,
                1,
                callCounter.count(),
                0,
                ExternalDataRateLimit.count(exception)
        );
    }

    private StoredPages collectAndStore(
            ExternalDataSource targetSource,
            LhAnnouncementRequest request,
            ExternalDataCallCounter callCounter
    ) {
        if (targetSource == ExternalDataSource.LH_ANNOUNCEMENT_DETAIL) {
            FetchedPages<LhAnnouncementDetailSource> pages = pageFetcher.fetchDetails(request, callCounter);
            int storedRowCount = sourceStore.replaceDetails(request.panId(), pages.items());
            return new StoredPages(storedRowCount, pages.requestDescriptions());
        }
        FetchedPages<LhAnnouncementSupplySource> pages = pageFetcher.fetchSupplies(request, callCounter);
        int storedRowCount = sourceStore.replaceSupplies(request.panId(), pages.items());
        return new StoredPages(storedRowCount, pages.requestDescriptions());
    }

    private record StoredPages(int storedRowCount, List<String> requestDescriptions) {
    }

}
