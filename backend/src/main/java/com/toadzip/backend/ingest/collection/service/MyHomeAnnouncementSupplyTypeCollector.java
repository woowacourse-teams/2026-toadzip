package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementSupplyType;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.MyHomeSourceStore;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.MyHomeAnnouncementResponseParser;
import java.util.ArrayList;
import java.util.List;
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

    public ExternalDataCollectionReport collect(
            String runId,
            MyHomeAnnouncementSupplyType supplyType,
            MyHomeAnnouncementCollectionRequest request
    ) {
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        List<MyHomeAnnouncementSourceSnapshot> snapshots;
        try {
            snapshots = fetchCompleteSupplyType(supplyType, request, callCounter);
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
        int storedRowCount = sourceStore.storeAnnouncements(runId, snapshots);
        return new ExternalDataCollectionReport(
                ExternalDataSource.MYHOME_ANNOUNCEMENT.operation(),
                storedRowCount,
                0,
                callCounter.count()
        );
    }

    private List<MyHomeAnnouncementSourceSnapshot> fetchCompleteSupplyType(
            MyHomeAnnouncementSupplyType supplyType,
            MyHomeAnnouncementCollectionRequest request,
            ExternalDataCallCounter callCounter
    ) {
        List<MyHomeAnnouncementSourceSnapshot> snapshots = new ArrayList<>();
        int expectedTotalCount = -1;
        for (int page = 1; page <= request.maxPages(); page++) {
            int currentPage = page;
            String requestDescription = request.requestDescription(supplyType, currentPage);
            ExternalDataResponse response = retryExecutor.execute(
                    ExternalDataSource.MYHOME_ANNOUNCEMENT,
                    requestDescription,
                    () -> externalRepository.fetch(supplyType, request, currentPage),
                    callCounter
            );
            failureRecorder.resolve(ExternalDataSource.MYHOME_ANNOUNCEMENT, requestDescription);
            ExternalDataPage<MyHomeAnnouncementSourceSnapshot> parsedPage = responseParser.parse(
                    response,
                    snapshots.size()
            );
            expectedTotalCount = requireConsistentTotalCount(expectedTotalCount, parsedPage.totalCount());
            snapshots.addAll(parsedPage.items());
            if (parsedPage.completesCollection(snapshots.size(), request.pageSize())) {
                return snapshots;
            }
        }
        throw new ExternalDataRequestException("마이홈 공고 조회가 최대 페이지 안에 끝나지 않았습니다.");
    }

    private int requireConsistentTotalCount(int expectedTotalCount, int actualTotalCount) {
        if (expectedTotalCount < 0 || expectedTotalCount == actualTotalCount) {
            return actualTotalCount;
        }
        throw new ExternalDataRequestException(
                "마이홈 공고 응답의 totalCount가 페이지마다 다릅니다."
        );
    }
}
