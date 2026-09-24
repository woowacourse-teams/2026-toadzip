package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementSupplyType;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.MyHomeSourceStore;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.MyHomeAnnouncementResponseParser;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MyHomeAnnouncementSupplyTypeCollector {

    private final MyHomeAnnouncementResponseParser responseParser;
    private final MyHomeAnnouncementExternalRepository externalRepository;
    private final MyHomeSourceStore sourceStore;
    private final ExternalDataFailureRecorder failureRecorder;
    private final ExternalDataRetryExecutor retryExecutor;
    private final MeterRegistry meterRegistry;

    public ExternalDataCollectionReport collect(
            String runId,
            MyHomeAnnouncementSupplyType supplyType,
            MyHomeAnnouncementCollectionRequest request
    ) {
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        FetchedSupplyType fetchedSupplyType;
        try {
            fetchedSupplyType = fetchCompleteSupplyType(supplyType, request, callCounter);
        }
        catch (ExternalDataCallFailureException | ExternalDataRequestException exception) {
            failureRecorder.record(
                    ExternalDataSource.MYHOME_ANNOUNCEMENT,
                    request.requestDescription(supplyType, 1),
                    exception,
                    log,
                    "마이홈 공고 공급유형 수집에 실패했습니다"
            );
            return new ExternalDataCollectionReport(
                    ExternalDataSource.MYHOME_ANNOUNCEMENT.operation(),
                    0,
                    1,
                    callCounter.count(),
                    0,
                    ExternalDataRateLimit.count(exception)
            );
        }
        int storedRowCount = meterRegistry.timer(
                "ingest.announcement.store", "source", ExternalDataSource.MYHOME_ANNOUNCEMENT.name()
        ).record(() -> sourceStore.storeAnnouncements(runId, fetchedSupplyType.snapshots()));
        resolveFailures(fetchedSupplyType.requestDescriptions());
        return new ExternalDataCollectionReport(
                ExternalDataSource.MYHOME_ANNOUNCEMENT.operation(),
                storedRowCount,
                0,
                callCounter.count()
        );
    }

    private FetchedSupplyType fetchCompleteSupplyType(
            MyHomeAnnouncementSupplyType supplyType,
            MyHomeAnnouncementCollectionRequest request,
            ExternalDataCallCounter callCounter
    ) {
        List<MyHomeAnnouncementSourceSnapshot> snapshots = new ArrayList<>();
        List<String> requestDescriptions = new ArrayList<>();
        Set<String> collectedSourceKeys = new HashSet<>();
        int expectedTotalCount = -1;
        for (int page = 1; page <= request.maxPages(); page++) {
            int currentPage = page;
            int expectedTotalCountForPage = expectedTotalCount;
            String requestDescription = request.requestDescription(supplyType, currentPage);
            ExternalDataPage<MyHomeAnnouncementSourceSnapshot> parsedPage = retryExecutor.execute(
                    ExternalDataSource.MYHOME_ANNOUNCEMENT,
                    requestDescription,
                    () -> parsePage(
                            supplyType,
                            request,
                            currentPage,
                            snapshots.size(),
                            expectedTotalCountForPage,
                            collectedSourceKeys
                    ),
                    callCounter
            );
            expectedTotalCount = parsedPage.totalCount();
            requestDescriptions.add(requestDescription);
            snapshots.addAll(parsedPage.items());
            for (MyHomeAnnouncementSourceSnapshot item : parsedPage.items()) {
                collectedSourceKeys.add(MyHomeAnnouncementSource.sourceKeyOf(item));
            }
            if (parsedPage.completesCollection(snapshots.size(), request.pageSize())) {
                return new FetchedSupplyType(snapshots, requestDescriptions);
            }
        }
        throw new ExternalDataRequestException("마이홈 공고 조회가 최대 페이지 안에 끝나지 않았습니다.");
    }

    private ExternalDataPage<MyHomeAnnouncementSourceSnapshot> parsePage(
            MyHomeAnnouncementSupplyType supplyType,
            MyHomeAnnouncementCollectionRequest request,
            int page,
            int collectedCount,
            int expectedTotalCount,
            Set<String> collectedSourceKeys
    ) {
        ExternalDataPage<MyHomeAnnouncementSourceSnapshot> parsedPage = responseParser.parse(
                externalRepository.fetch(supplyType, request, page),
                collectedCount
        );
        validateConsistentTotalCount(expectedTotalCount, parsedPage.totalCount());
        validateUniqueSourceKeys(parsedPage.items(), collectedSourceKeys);
        return parsedPage;
    }

    private void validateUniqueSourceKeys(
            List<MyHomeAnnouncementSourceSnapshot> items,
            Set<String> collectedSourceKeys
    ) {
        Set<String> pageSourceKeys = new HashSet<>();
        for (MyHomeAnnouncementSourceSnapshot item : items) {
            String sourceKey = MyHomeAnnouncementSource.sourceKeyOf(item);
            if (collectedSourceKeys.contains(sourceKey) || !pageSourceKeys.add(sourceKey)) {
                throw new ExternalDataRequestException("마이홈 공고 응답에 중복된 원천 키가 있습니다.");
            }
        }
    }

    private void validateConsistentTotalCount(int expectedTotalCount, int actualTotalCount) {
        if (expectedTotalCount < 0 || expectedTotalCount == actualTotalCount) {
            return;
        }
        throw new ExternalDataRequestException(
                "마이홈 공고 응답의 totalCount가 페이지마다 다릅니다."
        );
    }

    private void resolveFailures(List<String> requestDescriptions) {
        requestDescriptions.forEach(requestDescription -> failureRecorder.resolve(
                ExternalDataSource.MYHOME_ANNOUNCEMENT,
                requestDescription
        ));
    }

    private record FetchedSupplyType(
            List<MyHomeAnnouncementSourceSnapshot> snapshots,
            List<String> requestDescriptions
    ) {
    }
}
