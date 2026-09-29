package com.toadzip.backend.ingest.collection.service;

import static com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock.Operation.MYHOME_ANNOUNCEMENT_COLLECTION;

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
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 공급유형별로 모든 페이지를 검증한 뒤 원천을 저장한다.
 * 모든 공급유형의 수집이 성공한 경우에만 전체 원천의 조회 여부를 확정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyHomeAnnouncementCollectionService {

    private final IngestOperationLock executionLock;
    private final MyHomeSourceStore sourceStore;
    private final MyHomeAnnouncementResponseParser responseParser;
    private final MyHomeAnnouncementExternalRepository externalRepository;
    private final ExternalDataFailureRecorder failureRecorder;
    private final ExternalDataRetryExecutor retryExecutor;
    private final MeterRegistry meterRegistry;

    public ExternalDataCollectionReport collect(MyHomeAnnouncementCollectionRequest request) {
        return executionLock.tryRun(MYHOME_ANNOUNCEMENT_COLLECTION, () -> collectUnlocked(request))
                .orElseThrow(this::alreadyRunning);
    }

    private ExternalDataCollectionReport collectUnlocked(MyHomeAnnouncementCollectionRequest request) {
        String runId = UUID.randomUUID().toString();
        log.info(
                "마이홈 공고 수집을 시작합니다: runId={}, pageSize={}, maxPages={}",
                runId,
                request.pageSize(),
                request.maxPages()
        );
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty(
                ExternalDataSource.MYHOME_ANNOUNCEMENT.operation()
        );
        for (MyHomeAnnouncementSupplyType supplyType : MyHomeAnnouncementSupplyType.values()) {
            ExternalDataCollectionReport supplyTypeReport = collectSupplyType(
                    runId,
                    supplyType,
                    request
            );
            report = report.plus(supplyTypeReport);
            if (supplyTypeReport.rateLimitedRequestCount() > 0) {
                return report;
            }
        }
        if (report.failedRequestCount() == 0) {
            sourceStore.completeAnnouncementCollection(runId);
        }
        log.info(
                "마이홈 공고 수집을 완료했습니다: runId={}, storedRowCount={}, failedRequestCount={}, "
                        + "externalApiCallCount={}",
                runId,
                report.storedRowCount(),
                report.failedRequestCount(),
                report.externalApiCallCount()
        );
        return report;
    }

    private ExternalDataCollectionReport collectSupplyType(
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
        int storedRowCount = meterRegistry.timer(
                "ingest.announcement.store", "source", ExternalDataSource.MYHOME_ANNOUNCEMENT.name()
        ).record(() -> sourceStore.storeAnnouncements(runId, snapshots));
        failureRecorder.resolveStartingWith(
                ExternalDataSource.MYHOME_ANNOUNCEMENT,
                "suplyTy=" + supplyType.requestCode() + "&pageNo="
        );
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
        Set<String> collectedSourceKeys = new HashSet<>();
        int expectedTotalCount = -1;
        IngestExecutionScope.beginWork("마이홈 공고 · 공급유형 " + supplyType.requestCode(), "페이지", -1);
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
            IngestExecutionScope.pageCompleted(page, parsedPage.totalCount(), request.pageSize());
            expectedTotalCount = parsedPage.totalCount();
            snapshots.addAll(parsedPage.items());
            for (MyHomeAnnouncementSourceSnapshot item : parsedPage.items()) {
                collectedSourceKeys.add(MyHomeAnnouncementSource.sourceKeyOf(item));
            }
            if (parsedPage.completesCollection(snapshots.size(), request.pageSize())) {
                return snapshots;
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

    private IngestAlreadyRunningException alreadyRunning() {
        log.warn("마이홈 공고 수집이 이미 실행 중이므로 중복 실행을 건너뜁니다.");
        return new IngestAlreadyRunningException("마이홈 공고 수집이 이미 실행 중입니다.");
    }

}
